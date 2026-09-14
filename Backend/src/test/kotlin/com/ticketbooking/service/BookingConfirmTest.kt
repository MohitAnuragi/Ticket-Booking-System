package com.ticketbooking.service

import com.ticketbooking.dto.HoldRequest
import com.ticketbooking.model.BookingStatus
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
import com.ticketbooking.util.ForbiddenException
import com.ticketbooking.util.NotFoundException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BookingService.confirmBooking] - turning a hold into a sale.
 *
 * The rules that matter: only the owner may confirm, an expired hold can never be
 * confirmed, and the reference issued at hold time survives confirmation.
 */
class BookingConfirmTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)

    private val eventId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private val events = FakeEventRepository()
    private val seats = FakeSeatRepository()
    private val bookings = FakeBookingRepository()

    /** Rebuilt per test so the clock can be advanced between hold and confirm. */
    private fun serviceAt(moment: OffsetDateTime) = BookingService(
        bookings = bookings,
        seats = seats,
        events = events,
        transactions = DirectTransactionRunner(),
        config = BookingConfig(holdTtlHours = 24, maxSeatsPerBooking = 10),
        time = TimeProvider.fixedAt(moment),
    )

    private val service = serviceAt(now)

    init {
        bookings.eventResolver = { events.findByIdWithVenue(it) }
        bookings.seatResolver = { eventSeatId ->
            seats.findEventSeatMap(eventId)
                .firstOrNull { it.eventSeat.id == eventSeatId }
                ?.let { Triple(it.seat.seatRow, it.seat.seatNumber, it.seat.seatType) }
        }
        events.put(
            EventWithVenue(
                event = Event(
                    id = eventId,
                    venueId = venueId,
                    title = "Coldplay Live",
                    description = null,
                    category = "Concert",
                    startTime = LocalDateTime.of(2026, 10, 1, 19, 0),
                    endTime = LocalDateTime.of(2026, 10, 1, 22, 0),
                    basePrice = BigDecimal("1000.00"),
                    status = EventStatus.PUBLISHED,
                    posterUrl = null,
                ),
                venueName = "NSCI Dome",
                venueCity = "Mumbai",
                venueAddress = "1 Road",
            ),
        )
    }

    private fun holdOneSeat(
        userId: UUID = alice,
        row: String = "A",
        number: Int = 1,
        seatType: SeatType = SeatType.REGULAR,
        multiplier: String = "1.00",
    ): Pair<String, UUID> {
        val seatId = seats.addAvailableSeat(
            eventId, venueId, row, number, seatType, BigDecimal(multiplier),
        ).id
        val hold = service.holdSeats(userId, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        return hold.id to seatId
    }

    // ---------------- Happy path ----------------

    @Test
    fun `confirming a live hold books the seats`() {
        val (bookingId, seatId) = holdOneSeat()

        val confirmed = service.confirmBooking(alice, bookingId)

        assertEquals("CONFIRMED", confirmed.status)
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `confirmation clears the lock fields on the seats`() {
        val (bookingId, seatId) = holdOneSeat()

        service.confirmBooking(alice, bookingId)

        val seat = seats.eventSeat(seatId)!!
        // A sold seat has no holder and no deadline; a leftover deadline could later
        // be misread as an expired hold and put the seat back on sale.
        assertNull(seat.lockedBy)
        assertNull(seat.lockExpiresAt)
    }

    @Test
    fun `confirmation clears the booking's hold expiry`() {
        val (bookingId, _) = holdOneSeat()

        val confirmed = service.confirmBooking(alice, bookingId)

        assertNull(confirmed.expiresAt, "a confirmed booking has no hold deadline")
        assertNull(bookings.findById(UUID.fromString(bookingId))!!.holdExpiresAt)
    }

    @Test
    fun `the booking reference issued at hold time survives confirmation`() {
        val (bookingId, _) = holdOneSeat()
        val referenceAtHold = bookings.findById(UUID.fromString(bookingId))!!.bookingReference

        val confirmed = service.confirmBooking(alice, bookingId)

        // Regenerating would change the code the user was already shown.
        assertEquals(referenceAtHold, confirmed.bookingReference)
        assertTrue(confirmed.bookingReference.startsWith("TB-"))
    }

    @Test
    fun `confirmation preserves the seats and total from the hold`() {
        val seatA = seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.REGULAR, BigDecimal("1.00")).id
        val seatB = seats.addAvailableSeat(eventId, venueId, "B", 1, SeatType.PREMIUM, BigDecimal("1.50")).id
        val hold = service.holdSeats(
            alice,
            eventId.toString(),
            HoldRequest(listOf(seatA.toString(), seatB.toString())),
        )

        val confirmed = service.confirmBooking(alice, hold.id)

        assertEquals(hold.totalAmount, confirmed.totalAmount)
        assertEquals("2500.00", confirmed.totalAmount)
        assertEquals(listOf("A1", "B1"), confirmed.seatLabels)
    }

    // ---------------- Idempotency ----------------

    @Test
    fun `confirming twice is idempotent`() {
        val (bookingId, seatId) = holdOneSeat()

        val first = service.confirmBooking(alice, bookingId)
        val second = service.confirmBooking(alice, bookingId)

        // A double-clicked button must not error or change anything.
        assertEquals("CONFIRMED", second.status)
        assertEquals(first.bookingReference, second.bookingReference)
        assertEquals(first.totalAmount, second.totalAmount)
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
    }

    // ---------------- Ownership ----------------

    @Test
    fun `another user cannot confirm someone else's hold`() {
        val (bookingId, seatId) = holdOneSeat(userId = alice)

        assertFailsWith<ForbiddenException> { service.confirmBooking(bob, bookingId) }

        // Alice's hold must be untouched.
        assertEquals(BookingStatus.PENDING, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.LOCKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `ownership is checked before state so a foreign booking never leaks its status`() {
        val (bookingId, _) = holdOneSeat(userId = alice)
        service.confirmBooking(alice, bookingId)

        // Even for an already-confirmed booking, a stranger gets 403, not 200.
        assertFailsWith<ForbiddenException> { service.confirmBooking(bob, bookingId) }
    }

    // ---------------- Expiry ----------------

    @Test
    fun `an expired hold cannot be confirmed`() {
        val (bookingId, seatId) = holdOneSeat()

        // 24h TTL, so confirm one second past the deadline.
        val later = serviceAt(now.plusHours(24).plusSeconds(1))
        val error = assertFailsWith<ConflictException> { later.confirmBooking(alice, bookingId) }

        assertEquals("HOLD_EXPIRED", error.errorCode)
        // The lapsed hold is closed out and the seat returned to the market.
        assertEquals(BookingStatus.EXPIRED, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `a hold can be confirmed right up to its deadline`() {
        val (bookingId, _) = holdOneSeat()

        // One second before expiry.
        val justInTime = serviceAt(now.plusHours(24).minusSeconds(1))
        assertEquals("CONFIRMED", justInTime.confirmBooking(alice, bookingId).status)
    }

    @Test
    fun `a hold expiring exactly now is rejected`() {
        val (bookingId, _) = holdOneSeat()

        // Boundary matches the sweeper's `<= now` and EventSeat.isLockExpiredAt.
        val atDeadline = serviceAt(now.plusHours(24))
        assertFailsWith<ConflictException> { atDeadline.confirmBooking(alice, bookingId) }
    }

    @Test
    fun `an already-expired booking reports HOLD_EXPIRED`() {
        val (bookingId, _) = holdOneSeat()
        bookings.updateStatus(UUID.fromString(bookingId), BookingStatus.EXPIRED, clearHoldExpiry = true)

        val error = assertFailsWith<ConflictException> { service.confirmBooking(alice, bookingId) }
        assertEquals("HOLD_EXPIRED", error.errorCode)
    }

    // ---------------- Invalid states ----------------

    @Test
    fun `a cancelled booking cannot be confirmed`() {
        val (bookingId, _) = holdOneSeat()
        bookings.updateStatus(UUID.fromString(bookingId), BookingStatus.CANCELLED, clearHoldExpiry = true)

        val error = assertFailsWith<ConflictException> { service.confirmBooking(alice, bookingId) }
        assertEquals("BOOKING_CANCELLED", error.errorCode)
    }

    @Test
    fun `a booking whose seats were released cannot be confirmed`() {
        val (bookingId, _) = holdOneSeat()
        bookings.deactivateSeats(UUID.fromString(bookingId))

        val error = assertFailsWith<ConflictException> { service.confirmBooking(alice, bookingId) }
        assertEquals("BOOKING_HAS_NO_SEATS", error.errorCode)
    }

    @Test
    fun `confirming an unknown booking is a 404`() {
        assertFailsWith<NotFoundException> {
            service.confirmBooking(alice, UUID.randomUUID().toString())
        }
    }

    @Test
    fun `a malformed booking id is a validation error`() {
        val error = assertFailsWith<ValidationException> { service.confirmBooking(alice, "not-a-uuid") }
        assertEquals("id", error.field)
    }

    // ---------------- Interaction with re-holding ----------------

    @Test
    fun `a hold superseded by the user's own re-hold cannot be confirmed`() {
        val seatA = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val seatB = seats.addAvailableSeat(eventId, venueId, "A", 2).id

        val first = service.holdSeats(
            alice, eventId.toString(),
            HoldRequest(listOf(seatA.toString(), seatB.toString())),
        )
        // Alice changes her mind and re-holds only seat A.
        val second = service.holdSeats(alice, eventId.toString(), HoldRequest(listOf(seatA.toString())))

        // The abandoned booking is closed; only the current one is confirmable.
        assertFailsWith<ConflictException> { service.confirmBooking(alice, first.id) }
        assertEquals("CONFIRMED", service.confirmBooking(alice, second.id).status)

        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatA)!!.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatB)!!.status)
    }

    @Test
    fun `a booked seat cannot then be held by anyone else`() {
        val (bookingId, seatId) = holdOneSeat()
        service.confirmBooking(alice, bookingId)

        assertFailsWith<com.ticketbooking.util.SeatsUnavailableException> {
            service.holdSeats(bob, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        }
    }
}
