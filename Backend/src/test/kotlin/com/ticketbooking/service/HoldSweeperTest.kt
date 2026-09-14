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
import com.ticketbooking.util.TimeProvider
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [BookingService.sweepExpiredHolds] and the [HoldSweeper] loop around it.
 *
 * Why this matters: a hold marks seats LOCKED, and nothing in a request path is
 * guaranteed to run when that deadline passes. Without this job a seat nobody
 * looks at again is out of sale permanently.
 */
class HoldSweeperTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)

    private val eventId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private val events = FakeEventRepository()
    private val seats = FakeSeatRepository()
    private val bookings = FakeBookingRepository()

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
        at: OffsetDateTime = now,
    ): Pair<String, UUID> {
        val seatId = seats.addAvailableSeat(
            eventId, venueId, row, number, SeatType.REGULAR, BigDecimal("1.00"),
        ).id
        val hold = serviceAt(at)
            .holdSeats(userId, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        return hold.id to seatId
    }

    /** 24h TTL, so anything after this instant is past the deadline. */
    private val afterExpiry = now.plusHours(24).plusMinutes(1)

    // ---------------- The core rule ----------------

    @Test
    fun `sweeping does nothing while every hold is still live`() {
        val (bookingId, seatId) = holdOneSeat()

        val result = service.sweepExpiredHolds()

        assertTrue(result.didNothing, "a live hold must not be touched: $result")
        assertEquals(BookingStatus.PENDING, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.LOCKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `a lapsed hold is expired and its seats returned to the market`() {
        val (bookingId, seatId) = holdOneSeat()

        val result = serviceAt(afterExpiry).sweepExpiredHolds()

        assertEquals(1, result.holdsExpired)
        assertEquals(1, result.seatsReleased)
        assertEquals(BookingStatus.EXPIRED, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `sweeping clears the hold deadline and the seat's lock fields`() {
        val (bookingId, seatId) = holdOneSeat()

        serviceAt(afterExpiry).sweepExpiredHolds()

        assertNull(bookings.findById(UUID.fromString(bookingId))!!.holdExpiresAt)
        val seat = seats.eventSeat(seatId)!!
        assertNull(seat.lockedBy)
        assertNull(seat.lockExpiresAt)
    }

    @Test
    fun `a swept seat can immediately be held by another user`() {
        val (aliceHold, seatId) = holdOneSeat(userId = alice)

        val later = serviceAt(afterExpiry)
        later.sweepExpiredHolds()

        val bobHold = later.holdSeats(bob, eventId.toString(), HoldRequest(listOf(seatId.toString())))
        assertEquals("PENDING", bobHold.status)
        assertEquals(SeatStatus.LOCKED, seats.eventSeat(seatId)!!.status)
        assertEquals(bob, seats.eventSeat(seatId)!!.lockedBy)

        // Alice's lapsed hold cannot come back to life and take the seat from Bob.
        assertFailsWith<ConflictException> { later.confirmBooking(alice, aliceHold) }
    }

    @Test
    fun `a hold expiring exactly at the deadline is swept`() {
        val (bookingId, _) = holdOneSeat()

        // Boundary is inclusive, matching Booking.isHoldExpiredAt and confirmBooking.
        val result = serviceAt(now.plusHours(24)).sweepExpiredHolds()

        assertEquals(1, result.holdsExpired)
        assertEquals(BookingStatus.EXPIRED, bookings.findById(UUID.fromString(bookingId))!!.status)
    }

    // ---------------- What must NOT be swept ----------------

    @Test
    fun `confirmed bookings are never swept`() {
        val (bookingId, seatId) = holdOneSeat()
        service.confirmBooking(alice, bookingId)

        // Long past the original hold deadline - a sold seat has no deadline at all.
        val result = serviceAt(now.plusDays(30)).sweepExpiredHolds()

        assertTrue(result.didNothing, "a confirmed sale must survive any sweep: $result")
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(UUID.fromString(bookingId))!!.status)
        assertEquals(SeatStatus.BOOKED, seats.eventSeat(seatId)!!.status)
    }

    @Test
    fun `cancelled bookings are not swept again`() {
        val (bookingId, _) = holdOneSeat()
        service.cancelBooking(alice, bookingId)

        val result = serviceAt(afterExpiry).sweepExpiredHolds()

        assertTrue(result.didNothing)
        // CANCELLED must not be rewritten to EXPIRED: the two mean different things.
        assertEquals(BookingStatus.CANCELLED, bookings.findById(UUID.fromString(bookingId))!!.status)
    }

    @Test
    fun `sweeping twice is idempotent`() {
        holdOneSeat()
        val later = serviceAt(afterExpiry)

        val first = later.sweepExpiredHolds()
        val second = later.sweepExpiredHolds()

        assertEquals(1, first.holdsExpired)
        assertTrue(second.didNothing, "nothing left to do on the second pass: $second")
    }

    // ---------------- Batching and multi-hold behaviour ----------------

    @Test
    fun `every lapsed hold in a batch is released`() {
        val seatIds = (1..3).map { holdOneSeat(row = "A", number = it).second }

        val result = serviceAt(afterExpiry).sweepExpiredHolds()

        assertEquals(3, result.holdsExpired)
        assertEquals(3, result.seatsReleased)
        seatIds.forEach { assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(it)!!.status, "seat $it") }
    }

    @Test
    fun `a multi-seat hold releases all of its seats`() {
        val seatA = seats.addAvailableSeat(eventId, venueId, "A", 1).id
        val seatB = seats.addAvailableSeat(eventId, venueId, "A", 2).id
        service.holdSeats(
            alice,
            eventId.toString(),
            HoldRequest(listOf(seatA.toString(), seatB.toString())),
        )

        val result = serviceAt(afterExpiry).sweepExpiredHolds()

        assertEquals(1, result.holdsExpired)
        assertEquals(2, result.seatsReleased)
    }

    @Test
    fun `the batch limit caps one pass and the rest is picked up next time`() {
        (1..3).forEach { holdOneSeat(row = "B", number = it) }
        val later = serviceAt(afterExpiry)

        assertEquals(2, later.sweepExpiredHolds(batchLimit = 2).holdsExpired)
        assertEquals(1, later.sweepExpiredHolds(batchLimit = 2).holdsExpired)
        assertTrue(later.sweepExpiredHolds(batchLimit = 2).didNothing)
    }

    @Test
    fun `only lapsed holds are swept when live ones exist alongside them`() {
        val (oldHold, oldSeat) = holdOneSeat(row = "A", number = 1, at = now)
        // Held 23 hours later, so still live when the first one lapses.
        val (freshHold, freshSeat) = holdOneSeat(row = "A", number = 2, at = now.plusHours(23))

        val result = serviceAt(afterExpiry).sweepExpiredHolds()

        assertEquals(1, result.holdsExpired)
        assertEquals(BookingStatus.EXPIRED, bookings.findById(UUID.fromString(oldHold))!!.status)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(oldSeat)!!.status)
        assertEquals(BookingStatus.PENDING, bookings.findById(UUID.fromString(freshHold))!!.status)
        assertEquals(SeatStatus.LOCKED, seats.eventSeat(freshSeat)!!.status)
    }

    // ---------------- Orphaned locks ----------------

    @Test
    fun `a seat locked past its deadline with no booking behind it is rescued`() {
        // Reproduces a seat stranded by a crash: LOCKED with an elapsed deadline and
        // no booking row that would ever release it.
        val orphan = seats.addAvailableSeat(
            eventId, venueId, "C", 9, SeatType.REGULAR, BigDecimal("1.00"),
            status = SeatStatus.LOCKED,
            lockedBy = alice,
            lockExpiresAt = now.minusHours(1),
        ).id

        val result = service.sweepExpiredHolds()

        assertEquals(0, result.holdsExpired)
        assertEquals(1, result.orphanLocksReleased)
        assertEquals(SeatStatus.AVAILABLE, seats.eventSeat(orphan)!!.status)
    }

    @Test
    fun `seats released with their hold are not double-counted as orphans`() {
        holdOneSeat()

        val result = serviceAt(afterExpiry).sweepExpiredHolds()

        assertEquals(1, result.seatsReleased)
        // The hold's own seats are handled first and their lock fields cleared, so the
        // orphan pass must find nothing.
        assertEquals(0, result.orphanLocksReleased)
    }

    // ---------------- The HoldSweeper loop ----------------

    @Test
    fun `sweepOnce reports what the pass did`() {
        holdOneSeat()
        val expiring = serviceAt(afterExpiry)
        val sweeper = HoldSweeper(SweeperConfig(intervalMinutes = 5)) { expiring.sweepExpiredHolds(it) }

        val result = sweeper.sweepOnce()

        assertNotNull(result)
        assertEquals(1, result.holdsExpired)
        assertEquals(0, sweeper.failureCount)
    }

    @Test
    fun `sweepOnce passes the configured batch limit through`() {
        val seen = AtomicInteger(0)
        val sweeper = HoldSweeper(SweeperConfig(intervalMinutes = 5, batchLimit = 42)) { limit ->
            seen.set(limit)
            SweepResult()
        }

        sweeper.sweepOnce()

        assertEquals(42, seen.get())
    }

    @Test
    fun `a failing sweep is swallowed and counted, not propagated`() {
        val sweeper = HoldSweeper(SweeperConfig(intervalMinutes = 5)) {
            throw IllegalStateException("database went away")
        }

        // Must not throw: an exception escaping here would kill the loop, and a dead
        // sweeper fails silently while seats leak out of inventory.
        assertNull(sweeper.sweepOnce())
        assertNull(sweeper.sweepOnce())
        assertEquals(2, sweeper.failureCount)
    }

    @Test
    fun `the loop keeps sweeping after a failure and stops when cancelled`() = runBlocking {
        val passes = AtomicInteger(0)
        val sweeper = HoldSweeper(SweeperConfig(intervalMinutes = 0, batchLimit = 10)) {
            // First pass fails, later ones succeed - the loop must survive the first.
            if (passes.incrementAndGet() == 1) throw IllegalStateException("transient blip")
            SweepResult()
        }

        val job: Job = sweeper.start(CoroutineScope(coroutineContext))
        // intervalMinutes = 0 means no wait between passes, so this is quick. Bounded
        // so a stalled sweeper fails the test instead of hanging the build.
        var waited = 0
        while (passes.get() < 3 && job.isActive && waited < MAX_WAIT_TICKS) {
            delay(WAIT_TICK_MS)
            waited++
        }
        job.cancel()

        assertTrue(passes.get() >= 3, "loop ran only ${passes.get()} pass(es)")
        assertEquals(1, sweeper.failureCount)
        assertTrue(job.isCancelled)
    }

    private companion object {
        const val WAIT_TICK_MS = 5L
        const val MAX_WAIT_TICKS = 400 // ~2 seconds
    }
}
