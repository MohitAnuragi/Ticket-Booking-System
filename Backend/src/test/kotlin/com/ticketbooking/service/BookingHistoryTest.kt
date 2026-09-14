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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BookingService.listMyBookings], [BookingService.getBooking] and
 * [BookingService.cancelBooking].
 *
 * The rules that matter: history and detail never leak across users, cancelling
 * returns the seats to the market, and a confirmed ticket can only be cancelled
 * before the event starts (spec section 8).
 *
 * The headline case is `cancelling a booking frees the seat to be booked again` -
 * that path is exactly what the plain `UNIQUE(event_seat_id)` in spec section 5.2
 * made impossible and what the partial index `uq_booking_seats_active` fixes.
 */
class BookingHistoryTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)
    private val eventStart = LocalDateTime.of(2026, 10, 1, 19, 0)

    private val eventId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private val events = FakeEventRepository()
    private val seats = FakeSeatRepository()
    private val bookings = FakeBookingRepository()

    /** Rebuilt per test so the clock can be moved past the event's start time. */
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
                    startTime = eventStart,
                    endTime = eventStart.plusHours(3),
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

    /** Holds one freshly created seat and returns (bookingId, eventSeatId). */
    private fun holdOneSeat(
        userId: UUID = alice,
        row: String = "A",
        number: Int = 1,
        at: OffsetDateTime = now,
    ): Pair<String, UUID> {
        val seatId = seats.addAvailableSeat(
            eventId, venueId, row, number, SeatType.REGULAR, BigDecimal("1.00"),
        ).id
        val hold = serviceAt(at)
            .holdSeats(userId, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        return hold.id to seatId
    }

    // ---------------- History: GET /bookings/me ----------------

    @Test
    fun `history is empty for a user with no bookings`() {
        assertEquals(emptyList(), service.listMyBookings(alice))
    }

    @Test
    fun `history returns the caller's bookings newest first`() {
        val (older, _) = holdOneSeat(row = "A", number = 1, at = now)
        val (newer, _) = holdOneSeat(row = "A", number = 2, at = now.plusHours(1))

        val history = service.listMyBookings(alice)

        // Newest first, so the most recent activity is at the top of the page.
        assertEquals(listOf(newer, older), history.map { it.id })
    }

    @Test
    fun `history never includes another user's bookings`() {
        val (aliceBooking, _) = holdOneSeat(userId = alice, row = "A", number = 1)
        val (bobBooking, _) = holdOneSeat(userId = bob, row = "B", number = 1)

        assertEquals(listOf(aliceBooking), service.listMyBookings(alice).map { it.id })
        assertEquals(listOf(bobBooking), service.listMyBookings(bob).map { it.id })
    }

    @Test
    fun `a live hold in history carries its expiry so the UI can count down`() {
        holdOneSeat()

        val entry = service.listMyBookings(alice).single()

        assertEquals("PENDING", entry.status)
        assertNotNull(entry.expiresAt, "a PENDING hold must expose its deadline")
        assertTrue(entry.expiresAt!!.startsWith("2026-09-12T12:00"), "24h after the hold: ${entry.expiresAt}")
    }

    @Test
    fun `a confirmed booking in history has no expiry`() {
        val (bookingId, _) = holdOneSeat()
        service.confirmBooking(alice, bookingId)

        val entry = service.listMyBookings(alice).single()

        assertEquals("CONFIRMED", entry.status)
        assertNull(entry.expiresAt)
    }

    @Test
    fun `history keeps cancelled bookings with their seats as a record`() {
        val (bookingId, _) = holdOneSeat()
        service.confirmBooking(alice, bookingId)
        service.cancelBooking(alice, bookingId)

        val entry = service.listMyBookings(alice).single()

        // Deactivating the seat claim must not erase what the user had booked.
        assertEquals("CANCELLED", entry.status)
        assertEquals(listOf("A1"), entry.seatLabels)
    }

    @Test
    fun `history entries carry event and venue details without a second call`() {
        holdOneSeat()

        val entry = service.listMyBookings(alice).single()

        assertEquals("Coldplay Live", entry.event.title)
        assertEquals("NSCI Dome", entry.event.venueName)
        assertEquals("Mumbai", entry.event.venueCity)
    }

    // ---------------- Detail: GET /bookings/{id} ----------------

    @Test
    fun `the owner can read their booking`() {
        val (bookingId, _) = holdOneSeat()

        val detail = service.getBooking(alice, bookingId)

        assertEquals(bookingId, detail.id)
        assertEquals(listOf("A1"), detail.seatLabels)
        assertTrue(detail.bookingReference.startsWith("TB-"))
    }

    @Test
    fun `another user cannot read someone else's booking`() {
        val (bookingId, _) = holdOneSeat(userId = alice)

        assertFailsWith<ForbiddenException> { service.getBooking(bob, bookingId) }
    }

    @Test
    fun `reading an unknown booking is a 404`() {
        assertFailsWith<NotFoundException> { service.getBooking(alice, UUID.randomUUID().toString()) }
    }

    @Test
    fun `a malformed id on read is a validation error`() {
        val error = assertFailsWith<ValidationException> { service.getBooking(alice, "not-a-uuid") }
        assertEquals("id", error.field)
    }

    // ---------------- Cancellation: DELETE /bookings/{id} ----------------

    @Test
    fun `cancelling a hold releases its seats`() {
        val (bookingId, seatId) = holdOneSeat()

        val cancelled = service.cancelBooking(alice, bookingId)

        assertEquals("CANCELLED", cancelled.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `cancelling a confirmed booking releases its seats`() {
        val (bookingId, seatId) = holdOneSeat()
        service.confirmBooking(alice, bookingId)
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)

        val cancelled = service.cancelBooking(alice, bookingId)

        assertEquals("CANCELLED", cancelled.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `cancellation clears the hold deadline and the seat's lock fields`() {
        val (bookingId, seatId) = holdOneSeat()

        val cancelled = service.cancelBooking(alice, bookingId)

        assertNull(cancelled.expiresAt, "a cancelled booking has no hold deadline")
        val seat = seats.eventSeat(seatId)!!
        // A leftover holder or deadline could later be misread as a live hold.
        assertNull(seat.lockedBy)
        assertNull(seat.lockExpiresAt)
    }

    @Test
    fun `cancellation deactivates the seat claim but keeps the rows`() {
        val (bookingId, _) = holdOneSeat()
        val uuid = UUID.fromString(bookingId)

        service.cancelBooking(alice, bookingId)

        assertEquals(emptyList(), bookings.findActiveEventSeatIds(uuid))
        assertEquals(1, bookings.seatRowsFor(uuid).size, "history rows must survive")
        assertTrue(bookings.seatRowsFor(uuid).none { it.isActive })
    }

    /**
     * THE Task 11 demo, and the reason for the schema correction in db/02_deltas.sql.
     *
     * With the spec's plain `UNIQUE(event_seat_id)` on booking_seats, the cancelled
     * booking's row would still occupy the seat and this second hold would fail with
     * a constraint violation. [FakeBookingRepository.create] reproduces the partial
     * unique index, so this test genuinely exercises that rule.
     */
    @Test
    fun `cancelling a booking frees the seat to be booked again by someone else`() {
        val (aliceBooking, seatId) = holdOneSeat(userId = alice)
        service.confirmBooking(alice, aliceBooking)
        service.cancelBooking(alice, aliceBooking)

        val bobHold = service.holdSeats(bob, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        val bobBooking = service.confirmBooking(bob, bobHold.id)

        assertEquals("CONFIRMED", bobBooking.status)
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
        assertEquals(bob, bookings.findById(UUID.fromString(bobBooking.id))!!.userId)
        // Alice's cancellation stands; the seat changed hands cleanly.
        assertEquals(
            BookingStatus.CANCELLED,
            bookings.findById(UUID.fromString(aliceBooking))!!.status,
        )
    }

    @Test
    fun `the same user can re-book a seat they cancelled`() {
        val (first, seatId) = holdOneSeat(userId = alice)
        service.confirmBooking(alice, first)
        service.cancelBooking(alice, first)

        // An hour later, so the two bookings order deterministically in history.
        val later = serviceAt(now.plusHours(1))
        val second = later.holdSeats(alice, eventId.toString(), HoldRequest(listOf(seatId.toString())))

        assertEquals("CONFIRMED", later.confirmBooking(alice, second.id).status)
        // Two separate bookings in history, one cancelled and one live.
        assertEquals(
            listOf("CONFIRMED", "CANCELLED"),
            later.listMyBookings(alice).map { it.status },
        )
    }

    @Test
    fun `cancelling twice is idempotent`() {
        val (bookingId, seatId) = holdOneSeat()

        val first = service.cancelBooking(alice, bookingId)
        val second = service.cancelBooking(alice, bookingId)

        // A retried request must not error or disturb the seat.
        assertEquals("CANCELLED", second.status)
        assertEquals(first.bookingReference, second.bookingReference)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `cancelling twice does not free a seat someone else has since taken`() {
        val (aliceBooking, seatId) = holdOneSeat(userId = alice)
        service.cancelBooking(alice, aliceBooking)

        // Bob picks up the freed seat, then Alice's cancel request is retried.
        val bobHold = service.holdSeats(bob, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        service.confirmBooking(bob, bobHold.id)
        service.cancelBooking(alice, aliceBooking)

        // The idempotent branch returns early, so Bob's seat is untouched.
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `another user cannot cancel someone else's booking`() {
        val (bookingId, seatId) = holdOneSeat(userId = alice)

        assertFailsWith<ForbiddenException> { service.cancelBooking(bob, bookingId) }

        assertEquals(BookingStatus.PENDING, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.LOCKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `ownership is checked before state so a foreign booking never leaks its status`() {
        val (bookingId, _) = holdOneSeat(userId = alice)
        service.cancelBooking(alice, bookingId)

        // Even on the idempotent path, a stranger gets 403 rather than a 200.
        assertFailsWith<ForbiddenException> { service.cancelBooking(bob, bookingId) }
    }

    // ---------------- The cancellation window ----------------

    @Test
    fun `a confirmed booking cannot be cancelled once the event has started`() {
        val (bookingId, seatId) = holdOneSeat()
        service.confirmBooking(alice, bookingId)

        val afterStart = serviceAt(eventStart.plusMinutes(1).atOffset(ZoneOffset.UTC))
        val error = assertFailsWith<ConflictException> { afterStart.cancelBooking(alice, bookingId) }

        assertEquals("EVENT_ALREADY_STARTED", error.errorCode)
        // Nothing was released: the ticket is still valid for the show in progress.
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `a confirmed booking can be cancelled right up to the start time`() {
        val (bookingId, _) = holdOneSeat()
        service.confirmBooking(alice, bookingId)

        val justBefore = serviceAt(eventStart.minusSeconds(1).atOffset(ZoneOffset.UTC))
        assertEquals("CANCELLED", justBefore.cancelBooking(alice, bookingId).status)
    }

    @Test
    fun `cancellation is refused at exactly the start time`() {
        val (bookingId, _) = holdOneSeat()
        service.confirmBooking(alice, bookingId)

        // Event.hasStartedAt is inclusive, matching the hold check.
        val atStart = serviceAt(eventStart.atOffset(ZoneOffset.UTC))
        val error = assertFailsWith<ConflictException> { atStart.cancelBooking(alice, bookingId) }
        assertEquals("EVENT_ALREADY_STARTED", error.errorCode)
    }

    @Test
    fun `a lapsed but unswept hold can still be released by its owner`() {
        val (bookingId, seatId) = holdOneSeat()

        // Past the 24h TTL, before the sweeper has run. The booking is still PENDING,
        // so cancelling is the user tidying up their own abandoned hold.
        val later = serviceAt(now.plusHours(25))
        assertEquals("CANCELLED", later.cancelBooking(alice, bookingId).status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `an already-expired hold has nothing left to cancel`() {
        val (bookingId, _) = holdOneSeat()
        bookings.updateStatus(UUID.fromString(bookingId), BookingStatus.EXPIRED, clearHoldExpiry = true)

        val error = assertFailsWith<ConflictException> { service.cancelBooking(alice, bookingId) }
        assertEquals("HOLD_EXPIRED", error.errorCode)
    }

    @Test
    fun `cancelling an unknown booking is a 404`() {
        assertFailsWith<NotFoundException> {
            service.cancelBooking(alice, UUID.randomUUID().toString())
        }
    }

    @Test
    fun `a malformed booking id on cancel is a validation error`() {
        val error = assertFailsWith<ValidationException> { service.cancelBooking(alice, "not-a-uuid") }
        assertEquals("id", error.field)
    }

    // ---------------- Multi-seat bookings ----------------

    @Test
    fun `cancelling a multi-seat booking releases every seat`() {
        val seatA = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val seatB = seats.addAvailableSeat(eventId, venueId, "A", 2).id
        val seatC = seats.addAvailableSeat(eventId, venueId, "B", 5).id
        val hold = service.holdSeats(
            alice,
            eventId.toString(),
            HoldRequest(listOf(seatA.toString(), seatB.toString(), seatC.toString())),
        )
        service.confirmBooking(alice, hold.id)

        val cancelled = service.cancelBooking(alice, hold.id)

        assertEquals(listOf("A1", "A2", "B5"), cancelled.seatLabels)
        listOf(seatA, seatB, seatC).forEach { id ->
            assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(id)!!.status, "seat $id")
        }
    }
}
