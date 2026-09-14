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
 * [AdminBookingService] - oversight of every user's bookings.
 *
 * Two things separate this from the user-facing path and both are asserted here:
 * an admin sees WHO booked (the user block that user responses omit), and an admin
 * may cancel a booking they do not own, with no start-time window.
 */
class AdminBookingServiceTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)
    private val eventStart = LocalDateTime.of(2026, 10, 1, 19, 0)

    private val concertId = UUID.randomUUID()
    private val matchId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()

    private val admin = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private val events = FakeEventRepository()
    private val seats = FakeSeatRepository()
    private val bookings = FakeBookingRepository()

    private val service = AdminBookingService(
        bookings = bookings,
        seats = seats,
        transactions = DirectTransactionRunner(),
    )

    /** The user-facing service, used to create realistic data and to contrast rules. */
    private fun userServiceAt(moment: OffsetDateTime) = BookingService(
        bookings = bookings,
        seats = seats,
        events = events,
        transactions = DirectTransactionRunner(),
        config = BookingConfig(holdTtlHours = 24, maxSeatsPerBooking = 10),
        time = TimeProvider.fixedAt(moment),
    )

    private val userService = userServiceAt(now)

    init {
        bookings.eventResolver = { events.findByIdWithVenue(it) }
        bookings.seatResolver = { eventSeatId ->
            listOf(concertId, matchId)
                .flatMap { seats.findEventSeatMap(it) }
                .firstOrNull { it.eventSeat.id == eventSeatId }
                ?.let { Triple(it.seat.seatRow, it.seat.seatNumber, it.seat.seatType) }
        }
        bookings.userResolver = { userId ->
            when (userId) {
                alice -> "Alice Kapoor" to "alice@example.com"
                bob -> "Bob Mehta" to "bob@example.com"
                else -> null
            }
        }
        putEvent(concertId, "Coldplay Live")
        putEvent(matchId, "India vs Australia")
    }

    private fun putEvent(id: UUID, title: String) = events.put(
        EventWithVenue(
            event = Event(
                id = id,
                venueId = venueId,
                title = title,
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

    /** Creates a hold and returns (bookingId, eventSeatId). */
    private fun hold(
        userId: UUID,
        eventId: UUID = concertId,
        row: String = "A",
        number: Int = 1,
        at: OffsetDateTime = now,
    ): Pair<String, UUID> {
        val seatId = seats.addAvailableSeat(
            eventId, venueId, row, number, SeatType.REGULAR, BigDecimal("1.00"),
        ).id
        val booking = userServiceAt(at)
            .holdSeats(userId, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        return booking.id to seatId
    }

    private fun confirmedBooking(
        userId: UUID,
        eventId: UUID = concertId,
        row: String = "A",
        number: Int = 1,
        at: OffsetDateTime = now,
    ): Pair<String, UUID> {
        val (bookingId, seatId) = hold(userId, eventId, row, number, at)
        userServiceAt(at).confirmBooking(userId, bookingId)
        return bookingId to seatId
    }

    // ---------------- Listing ----------------

    @Test
    fun `an empty database lists nothing`() {
        val page = service.listBookings()

        assertEquals(emptyList(), page.bookings)
        assertEquals(0L, page.total)
        assertEquals(50, page.limit, "default page size")
        assertEquals(0L, page.offset)
    }

    @Test
    fun `listing spans every user, newest first`() {
        val (aliceBooking, _) = hold(alice, row = "A", number = 1, at = now)
        val (bobBooking, _) = hold(bob, row = "A", number = 2, at = now.plusHours(1))

        val page = service.listBookings()

        assertEquals(listOf(bobBooking, aliceBooking), page.bookings.map { it.id })
        assertEquals(2L, page.total)
    }

    @Test
    fun `admin rows say who booked, which user-facing rows never do`() {
        val (bookingId, _) = hold(alice)

        val adminRow = service.listBookings().bookings.single()
        assertNotNull(adminRow.user)
        assertEquals("Alice Kapoor", adminRow.user!!.name)
        assertEquals("alice@example.com", adminRow.user!!.email)

        // The same booking through the user's own endpoint carries no user block.
        assertNull(userService.getBooking(alice, bookingId).user)
    }

    @Test
    fun `filtering by event narrows the listing`() {
        val (concertBooking, _) = hold(alice, eventId = concertId, row = "A", number = 1)
        hold(bob, eventId = matchId, row = "A", number = 1)

        val page = service.listBookings(rawEventId = concertId.toString())

        assertEquals(listOf(concertBooking), page.bookings.map { it.id })
        assertEquals(1L, page.total)
    }

    @Test
    fun `filtering by user narrows the listing`() {
        hold(alice, row = "A", number = 1)
        val (bobBooking, _) = hold(bob, row = "A", number = 2)

        val page = service.listBookings(rawUserId = bob.toString())

        assertEquals(listOf(bobBooking), page.bookings.map { it.id })
    }

    @Test
    fun `filtering by status narrows the listing`() {
        val (confirmed, _) = confirmedBooking(alice, row = "A", number = 1)
        hold(bob, row = "A", number = 2) // stays PENDING

        assertEquals(listOf(confirmed), service.listBookings(rawStatus = "CONFIRMED").bookings.map { it.id })
        assertEquals(1L, service.listBookings(rawStatus = "PENDING").total)
        assertEquals(0L, service.listBookings(rawStatus = "CANCELLED").total)
    }

    @Test
    fun `status matching is case-insensitive`() {
        confirmedBooking(alice)

        assertEquals(1L, service.listBookings(rawStatus = "confirmed").total)
    }

    @Test
    fun `filters combine with AND`() {
        val (wanted, _) = confirmedBooking(alice, eventId = concertId, row = "A", number = 1)
        confirmedBooking(bob, eventId = concertId, row = "A", number = 2) // wrong user
        confirmedBooking(alice, eventId = matchId, row = "A", number = 1) // wrong event
        hold(alice, eventId = concertId, row = "A", number = 3) // wrong status

        val page = service.listBookings(
            rawEventId = concertId.toString(),
            rawUserId = alice.toString(),
            rawStatus = "CONFIRMED",
        )

        assertEquals(listOf(wanted), page.bookings.map { it.id })
        assertEquals(1L, page.total)
    }

    @Test
    fun `blank filter values are treated as absent`() {
        hold(alice)

        // Query strings like ?eventId=&status= must not be read as real filters.
        val page = service.listBookings(rawEventId = "", rawUserId = "  ", rawStatus = "")

        assertEquals(1L, page.total)
    }

    // ---------------- Pagination ----------------

    @Test
    fun `total counts every match, not just the current page`() {
        repeat(5) { hold(alice, row = "A", number = it + 1, at = now.plusMinutes(it.toLong())) }

        val page = service.listBookings(rawLimit = "2")

        assertEquals(2, page.bookings.size)
        // "Showing 1-2 of 5" is only possible if total ignores limit/offset.
        assertEquals(5L, page.total)
        assertEquals(2, page.limit)
        assertEquals(0L, page.offset)
    }

    @Test
    fun `offset walks through the pages without repeating or skipping a row`() {
        val created = (1..5).map { hold(alice, row = "A", number = it, at = now.plusMinutes(it.toLong())).first }
        val newestFirst = created.reversed()

        val first = service.listBookings(rawLimit = "2", rawOffset = "0").bookings.map { it.id }
        val second = service.listBookings(rawLimit = "2", rawOffset = "2").bookings.map { it.id }
        val third = service.listBookings(rawLimit = "2", rawOffset = "4").bookings.map { it.id }

        assertEquals(newestFirst, first + second + third)
        assertEquals(1, third.size, "last page is partial")
    }

    @Test
    fun `an offset past the end returns an empty page but the real total`() {
        hold(alice)

        val page = service.listBookings(rawOffset = "100")

        assertEquals(emptyList(), page.bookings)
        assertEquals(1L, page.total)
        assertEquals(100L, page.offset)
    }

    // ---------------- Filter validation ----------------

    @Test
    fun `an unknown status is rejected rather than silently ignored`() {
        val error = assertFailsWith<ValidationException> { service.listBookings(rawStatus = "PAID") }
        assertEquals("status", error.field)
        // The message must list what IS accepted.
        assertTrue(error.message.contains("CONFIRMED"), error.message)
    }

    @Test
    fun `a malformed event or user id is a 400, not a 500`() {
        assertEquals(
            "eventId",
            assertFailsWith<ValidationException> { service.listBookings(rawEventId = "nope") }.field,
        )
        assertEquals(
            "userId",
            assertFailsWith<ValidationException> { service.listBookings(rawUserId = "nope") }.field,
        )
    }

    @Test
    fun `a non-numeric limit or offset is rejected`() {
        assertEquals(
            "limit",
            assertFailsWith<ValidationException> { service.listBookings(rawLimit = "lots") }.field,
        )
        assertEquals(
            "offset",
            assertFailsWith<ValidationException> { service.listBookings(rawOffset = "later") }.field,
        )
    }

    @Test
    fun `an out-of-range limit is refused instead of quietly clamped`() {
        // Clamping would make an admin who asked for 5000 rows conclude there are
        // only 200 bookings.
        assertEquals(
            "limit",
            assertFailsWith<ValidationException> { service.listBookings(rawLimit = "0") }.field,
        )
        assertEquals(
            "limit",
            assertFailsWith<ValidationException> { service.listBookings(rawLimit = "5000") }.field,
        )
        assertEquals(200, service.listBookings(rawLimit = "200").limit, "200 is the accepted maximum")
    }

    @Test
    fun `a negative offset is refused`() {
        val error = assertFailsWith<ValidationException> { service.listBookings(rawOffset = "-1") }
        assertEquals("offset", error.field)
    }

    // ---------------- Detail ----------------

    @Test
    fun `an admin can read any user's booking, with their contact details`() {
        val (bookingId, _) = confirmedBooking(alice)

        val detail = service.getBooking(bookingId)

        assertEquals(bookingId, detail.id)
        assertEquals("CONFIRMED", detail.status)
        assertEquals("alice@example.com", detail.user!!.email)
        assertEquals(listOf("A1"), detail.seatLabels)
    }

    @Test
    fun `reading an unknown booking is a 404`() {
        assertFailsWith<NotFoundException> { service.getBooking(UUID.randomUUID().toString()) }
    }

    @Test
    fun `a malformed booking id is a validation error`() {
        assertEquals("id", assertFailsWith<ValidationException> { service.getBooking("nope") }.field)
    }

    // ---------------- Override cancellation ----------------

    @Test
    fun `an admin can cancel a booking they do not own`() {
        val (bookingId, seatId) = confirmedBooking(alice)

        val cancelled = service.cancelBooking(admin, bookingId)

        assertEquals("CANCELLED", cancelled.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
        // Still attributed to Alice - an admin cancelling does not take ownership.
        assertEquals(alice, bookings.findById(UUID.fromString(bookingId))!!.userId)
    }

    @Test
    fun `an admin can cancel a hold, releasing its seats`() {
        val (bookingId, seatId) = hold(alice)

        service.cancelBooking(admin, bookingId)

        assertEquals(BookingStatus.CANCELLED, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
        assertNull(seats.eventSeat(seatId)!!.lockedBy)
    }

    @Test
    fun `an admin can cancel after the event has started, where the customer cannot`() {
        val (bookingId, seatId) = confirmedBooking(alice)
        val afterStart = eventStart.plusMinutes(30).atOffset(ZoneOffset.UTC)

        // The customer is refused: the cancellation window closed at start time.
        val error = assertFailsWith<ConflictException> {
            userServiceAt(afterStart).cancelBooking(alice, bookingId)
        }
        assertEquals("EVENT_ALREADY_STARTED", error.errorCode)

        // The admin is not bound by that window - rescheduling and refunds need it.
        assertEquals("CANCELLED", service.cancelBooking(admin, bookingId).status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `admin cancellation is idempotent`() {
        val (bookingId, seatId) = confirmedBooking(alice)

        val first = service.cancelBooking(admin, bookingId)
        val second = service.cancelBooking(admin, bookingId)

        assertEquals("CANCELLED", second.status)
        assertEquals(first.bookingReference, second.bookingReference)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `an admin cancelling twice does not disturb a seat since resold`() {
        val (aliceBooking, seatId) = confirmedBooking(alice)
        service.cancelBooking(admin, aliceBooking)

        // Bob buys the freed seat, then the admin's request is retried.
        val (bobBooking, _) = run {
            val hold = userService.holdSeats(bob, concertId.toString(), HoldRequest(listOf(seatId.toString())))
            userService.confirmBooking(bob, hold.id)
            hold.id to seatId
        }
        service.cancelBooking(admin, aliceBooking)

        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(UUID.fromString(bobBooking))!!.status)
    }

    @Test
    fun `an expired hold has nothing left for an admin to cancel`() {
        val (bookingId, _) = hold(alice)
        bookings.updateStatus(UUID.fromString(bookingId), BookingStatus.EXPIRED, clearHoldExpiry = true)

        val error = assertFailsWith<ConflictException> { service.cancelBooking(admin, bookingId) }
        assertEquals("HOLD_EXPIRED", error.errorCode)
    }

    @Test
    fun `cancelling an unknown booking is a 404`() {
        assertFailsWith<NotFoundException> {
            service.cancelBooking(admin, UUID.randomUUID().toString())
        }
    }

    @Test
    fun `a cancelled booking still appears in the admin listing`() {
        val (bookingId, _) = confirmedBooking(alice)
        service.cancelBooking(admin, bookingId)

        val page = service.listBookings(rawStatus = "CANCELLED")

        assertEquals(listOf(bookingId), page.bookings.map { it.id })
        assertEquals(listOf("A1"), page.bookings.single().seatLabels)
    }
}
