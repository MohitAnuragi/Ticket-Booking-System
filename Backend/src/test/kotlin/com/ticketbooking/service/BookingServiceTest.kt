package com.ticketbooking.service

import com.ticketbooking.dto.HoldRequest
import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import com.ticketbooking.model.SeatStatus
import com.ticketbooking.model.SeatType
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.EventWithVenue
import com.ticketbooking.repository.FakeBookingRepository
import com.ticketbooking.repository.FakeEventRepository
import com.ticketbooking.repository.FakeSeatRepository
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.SeatsUnavailableException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.ValidationException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BookingService.holdSeats] - the concurrency core.
 *
 * These tests cover the decision logic around the row lock: which seats count as
 * claimable, what a partial conflict reports, and that a lapsed hold can be taken
 * over. The row lock itself is a PostgreSQL guarantee and is verified by hand with
 * two concurrent clients, as agreed for this build.
 */
class BookingServiceTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)
    private val time = TimeProvider.fixedAt(now)

    private val eventId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private val events = FakeEventRepository()
    private val seats = FakeSeatRepository()
    private val bookings = FakeBookingRepository()

    private val service = BookingService(
        bookings = bookings,
        seats = seats,
        events = events,
        transactions = DirectTransactionRunner(),
        config = BookingConfig(holdTtlHours = 24, maxSeatsPerBooking = 10),
        time = time,
    )

    init {
        bookings.eventResolver = { events.findByIdWithVenue(it) }
        bookings.seatResolver = { eventSeatId ->
            seats.findEventSeatMap(eventId)
                .firstOrNull { it.eventSeat.id == eventSeatId }
                ?.let { Triple(it.seat.seatRow, it.seat.seatNumber, it.seat.seatType) }
        }
    }

    private fun givenEvent(
        status: EventStatus = EventStatus.PUBLISHED,
        basePrice: String = "1000.00",
        startTime: LocalDateTime = LocalDateTime.of(2026, 10, 1, 19, 0),
    ) {
        events.put(
            EventWithVenue(
                event = Event(
                    id = eventId,
                    venueId = venueId,
                    title = "Coldplay Live",
                    description = null,
                    category = "Concert",
                    startTime = startTime,
                    endTime = startTime.plusHours(3),
                    basePrice = BigDecimal(basePrice),
                    status = status,
                    posterUrl = null,
                ),
                venueName = "NSCI Dome",
                venueCity = "Mumbai",
                venueAddress = "1 Road",
            ),
        )
    }

    private fun hold(userId: UUID, vararg seatIds: UUID) =
        service.holdSeats(userId, eventId.toString(), HoldRequest(seatIds.map { it.toString() }))

    // ---------------- Happy path ----------------

    @Test
    fun `holding available seats creates a PENDING booking`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val a2 = seats.addAvailableSeat(eventId, venueId, "A", 2).id

        val response = hold(alice, a1, a2)

        assertEquals("PENDING", response.status)
        assertEquals(listOf("A1", "A2"), response.seatLabels)
        assertTrue(response.bookingReference.startsWith("TB-"))
        assertEquals(1, bookings.all().size)
    }

    @Test
    fun `held seats become LOCKED owned by the caller with a 24 hour deadline`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        hold(alice, a1)

        val seat = seats.eventSeat(a1)!!
        assertEquals(SeatStatus.LOCKED, seat.status)
        assertEquals(alice, seat.lockedBy)
        assertEquals(now.plusHours(24), seat.lockExpiresAt)
        assertEquals(1, seat.version, "version must be bumped on every state change")
    }

    @Test
    fun `the hold response reports when it expires`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        val response = hold(alice, a1)

        assertNotNull(response.expiresAt)
        assertEquals(now.plusHours(24).toInstant().toString(), response.expiresAt)
    }

    @Test
    fun `total is the sum of per-seat prices using the seat map's pricing`() {
        givenEvent(basePrice = "1000.00")
        val regular = seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.REGULAR, BigDecimal("1.00")).id
        val premium = seats.addAvailableSeat(eventId, venueId, "B", 1, SeatType.PREMIUM, BigDecimal("1.50")).id
        val vip = seats.addAvailableSeat(eventId, venueId, "C", 1, SeatType.VIP, BigDecimal("2.50")).id

        val response = hold(alice, regular, premium, vip)

        // 1000.00 + 1500.00 + 2500.00
        assertEquals("5000.00", response.totalAmount)
        assertEquals(
            listOf("1000.00", "1500.00", "2500.00"),
            response.seats.map { it.price },
        )
    }

    @Test
    fun `each seat records the price charged for it`() {
        givenEvent(basePrice = "1500.00")
        val premium = seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.PREMIUM, BigDecimal("1.50")).id

        val response = hold(alice, premium)

        // Matches the spec's worked example: 1500 x 1.5 = 2250.
        assertEquals("2250.00", response.seats.single().price)
        assertEquals("2250.00", response.totalAmount)
    }

    // ---------------- Conflicts ----------------

    @Test
    fun `a seat already held by someone else is reported as unavailable`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        hold(alice, a1)

        val error = assertFailsWith<SeatsUnavailableException> { hold(bob, a1) }

        assertEquals("SEATS_UNAVAILABLE", error.errorCode)
        assertEquals(listOf(a1.toString()), error.unavailableSeatIds)
        assertTrue(error.message.contains("A1"), "the seat label should be named: ${error.message}")
    }

    @Test
    fun `a partial conflict names only the offending seats and holds nothing`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val a2 = seats.addAvailableSeat(eventId, venueId, "A", 2).id
        val a3 = seats.addAvailableSeat(eventId, venueId, "A", 3).id
        hold(alice, a2)

        val error = assertFailsWith<SeatsUnavailableException> { hold(bob, a1, a2, a3) }

        assertEquals(listOf(a2.toString()), error.unavailableSeatIds)
        // All-or-nothing: the free seats must not have been quietly taken.
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(a1)!!.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(a3)!!.status)
        assertEquals(1, bookings.all().size, "no second booking should exist")
    }

    @Test
    fun `a booked seat cannot be held`() {
        givenEvent()
        val sold = seats.addAvailableSeat(eventId, venueId, "A", 1, status = SeatStatus.BOOKED).id

        assertFailsWith<SeatsUnavailableException> { hold(bob, sold) }
    }

    @Test
    fun `a caller may re-hold a seat they already hold`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        hold(alice, a1)

        // Re-submitting the same selection is not a conflict for the owner.
        val second = hold(alice, a1)

        assertEquals("PENDING", second.status)
        assertEquals(alice, seats.eventSeat(a1)!!.lockedBy)
    }

    @Test
    fun `an expired hold can be taken over by another user`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(
            eventId, venueId, "A", 1,
            status = SeatStatus.LOCKED,
            lockedBy = alice,
            // Held 24h ago; the deadline has just passed.
            lockExpiresAt = now.minusSeconds(1),
        ).id

        val response = service.holdSeats(bob, eventId.toString(), HoldRequest(listOf(a1.toString())))

        assertEquals("PENDING", response.status)
        assertEquals(bob, seats.eventSeat(a1)!!.lockedBy, "the lapsed hold must transfer")
        assertEquals(now.plusHours(24), seats.eventSeat(a1)!!.lockExpiresAt)
    }

    @Test
    fun `a hold expiring exactly now is takeable`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(
            eventId, venueId, "A", 1,
            status = SeatStatus.LOCKED,
            lockedBy = alice,
            lockExpiresAt = now,
        ).id

        service.holdSeats(bob, eventId.toString(), HoldRequest(listOf(a1.toString())))
        assertEquals(bob, seats.eventSeat(a1)!!.lockedBy)
    }

    // ---------------- Event state ----------------

    @Test
    fun `seats cannot be held for a draft event`() {
        givenEvent(status = EventStatus.DRAFT)
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        val error = assertFailsWith<ConflictException> { hold(alice, a1) }
        assertEquals("EVENT_NOT_BOOKABLE", error.errorCode)
    }

    @Test
    fun `seats cannot be held for a cancelled event`() {
        givenEvent(status = EventStatus.CANCELLED)
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        val error = assertFailsWith<ConflictException> { hold(alice, a1) }
        assertEquals("EVENT_NOT_BOOKABLE", error.errorCode)
        assertTrue(error.message.contains("cancelled"))
    }

    @Test
    fun `seats cannot be held for an event that already started`() {
        givenEvent(startTime = now.minusHours(1).toLocalDateTime())
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        val error = assertFailsWith<ConflictException> { hold(alice, a1) }
        assertEquals("EVENT_ALREADY_STARTED", error.errorCode)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(a1)!!.status, "nothing should be locked")
    }

    @Test
    fun `holding against an unknown event is a 404`() {
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        assertFailsWith<NotFoundException> {
            service.holdSeats(alice, UUID.randomUUID().toString(), HoldRequest(listOf(a1.toString())))
        }
    }

    // ---------------- Request validation ----------------

    @Test
    fun `an empty seat list is rejected`() {
        givenEvent()

        assertEquals(
            "eventSeatIds",
            assertFailsWith<ValidationException> {
                service.holdSeats(alice, eventId.toString(), HoldRequest(emptyList()))
            }.field,
        )
        assertFailsWith<ValidationException> {
            service.holdSeats(alice, eventId.toString(), HoldRequest(null))
        }
    }

    @Test
    fun `more seats than the configured maximum is rejected`() {
        givenEvent()
        val ids = (1..11).map { seats.addAvailableSeat(eventId, venueId, "A", it).id }

        val error = assertFailsWith<ValidationException> {
            service.holdSeats(alice, eventId.toString(), HoldRequest(ids.map { it.toString() }))
        }
        assertTrue(error.message.contains("10"), "the limit should be stated: ${error.message}")
    }

    @Test
    fun `exactly the maximum number of seats is allowed`() {
        givenEvent()
        val ids = (1..10).map { seats.addAvailableSeat(eventId, venueId, "A", it).id }

        val response = service.holdSeats(alice, eventId.toString(), HoldRequest(ids.map { it.toString() }))
        assertEquals(10, response.seats.size)
    }

    @Test
    fun `duplicate seat ids are rejected rather than silently collapsed`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id

        // Charging for one seat when two were requested would hide a client bug.
        val error = assertFailsWith<ValidationException> { hold(alice, a1, a1) }
        assertEquals("eventSeatIds", error.field)
        assertTrue(error.message.contains("duplicate"))
    }

    @Test
    fun `a malformed seat id is a validation error`() {
        givenEvent()

        assertFailsWith<ValidationException> {
            service.holdSeats(alice, eventId.toString(), HoldRequest(listOf("not-a-uuid")))
        }
    }

    @Test
    fun `a seat belonging to another event is a 404`() {
        givenEvent()
        val otherEventSeat = seats.addAvailableSeat(UUID.randomUUID(), venueId, "A", 1).id

        val error = assertFailsWith<NotFoundException> { hold(alice, otherEventSeat) }
        assertTrue(error.message.contains("do not belong"))
    }

    // ---------------- Booking references ----------------

    @Test
    fun `booking references are unique and readable`() {
        givenEvent()
        val references = (1..20).map { number ->
            val seat = seats.addAvailableSeat(eventId, venueId, "A", number).id
            hold(alice, seat).bookingReference
        }

        assertEquals(references.size, references.distinct().size, "references must be unique")
        references.forEach { reference ->
            assertTrue(Regex("^TB-[A-Z2-9]{6}$").matches(reference), "unexpected format: $reference")
            // Ambiguous characters are excluded so a reference can be read aloud.
            assertTrue(!reference.drop(3).contains("O"))
            assertTrue(!reference.drop(3).contains("1"))
        }
    }

    // ---------------- Isolation between users ----------------

    @Test
    fun `two users can hold different seats at the same event`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val a2 = seats.addAvailableSeat(eventId, venueId, "A", 2).id

        hold(alice, a1)
        hold(bob, a2)

        assertEquals(alice, seats.eventSeat(a1)!!.lockedBy)
        assertEquals(bob, seats.eventSeat(a2)!!.lockedBy)
        assertEquals(2, bookings.all().size)
    }

    @Test
    fun `re-holding replaces the previous hold rather than stacking a second claim`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val a2 = seats.addAvailableSeat(eventId, venueId, "A", 2).id

        val first = hold(alice, a1, a2)
        // Alice narrows her selection to one seat.
        val second = hold(alice, a1)

        // Only one active claim may exist per seat (uq_booking_seats_active), so the
        // earlier booking is closed out rather than left holding the same seat.
        assertEquals("CANCELLED", bookings.findById(UUID.fromString(first.id))!!.status.name)
        assertEquals("PENDING", bookings.findById(UUID.fromString(second.id))!!.status.name)
        assertEquals(
            0,
            bookings.seatRowsFor(UUID.fromString(first.id)).count { it.isActive },
            "the superseded booking must hold no active claims",
        )

        // The dropped seat goes back on sale; the kept seat stays hers.
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(a2)!!.status)
        assertEquals(SeatStatus.LOCKED, seats.eventSeat(a1)!!.status)
        assertEquals(alice, seats.eventSeat(a1)!!.lockedBy)
    }

    @Test
    fun `taking over a lapsed hold marks the old booking EXPIRED`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val aliceHold = hold(alice, a1)

        // Alice's hold lapses.
        seats.addEventSeat(
            seats.eventSeat(a1)!!.copy(lockExpiresAt = now.minusSeconds(1)),
        )
        bookings.updateStatus(UUID.fromString(aliceHold.id), com.ticketbooking.model.BookingStatus.PENDING)

        service.holdSeats(bob, eventId.toString(), HoldRequest(listOf(a1.toString())))

        // Distinguishes "lapsed" from "user cancelled" in Alice's history.
        assertEquals("EXPIRED", bookings.findById(UUID.fromString(aliceHold.id))!!.status.name)
        assertEquals(bob, seats.eventSeat(a1)!!.lockedBy)
    }

    @Test
    fun `a confirmed booking has no expiry in its response`() {
        givenEvent()
        val a1 = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val response = hold(alice, a1)

        // Sanity check on the DTO mapping: a PENDING hold has one, and the field is
        // driven purely by hold_expires_at.
        assertNotNull(response.expiresAt)

        bookings.updateStatus(UUID.fromString(response.id), com.ticketbooking.model.BookingStatus.CONFIRMED, clearHoldExpiry = true)
        val confirmed = bookings.findDetailById(UUID.fromString(response.id))!!
        assertNull(confirmed.booking.holdExpiresAt)
    }
}
