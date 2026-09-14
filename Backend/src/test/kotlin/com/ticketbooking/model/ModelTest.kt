package com.ticketbooking.model

import com.ticketbooking.util.ValidationException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 3 verification for the domain model.
 *
 * The lock-expiry rules tested here are the foundation of the 24-hour hold: if
 * [EventSeat.effectiveStatus] were wrong, an expired hold would either block a
 * seat forever or release it early.
 */
class ModelTest {

    private val now: OffsetDateTime = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)

    // ---------------- Enum parsing ----------------

    @Test
    fun `role parsing is case insensitive and defaults to USER`() {
        assertEquals(Role.ADMIN, Role.from("ADMIN"))
        assertEquals(Role.ADMIN, Role.from("admin"))
        assertEquals(Role.USER, Role.from("USER"))
        // Unrecognised stored values must never silently escalate to ADMIN.
        assertEquals(Role.USER, Role.from("superuser"))
        assertEquals(Role.USER, Role.from(""))
    }

    @Test
    fun `seat type strict parse rejects unknown tiers`() {
        assertEquals(SeatType.VIP, SeatType.parse("vip"))
        assertEquals(SeatType.PREMIUM, SeatType.parse("PREMIUM"))

        val error = assertFailsWith<ValidationException> { SeatType.parse("GOLD") }
        assertEquals("seatType", error.field)
        assertTrue(error.message.contains("REGULAR"), "message should list valid tiers")
    }

    @Test
    fun `event status strict parse rejects unknown values`() {
        assertEquals(EventStatus.PUBLISHED, EventStatus.parse("published"))
        assertFailsWith<ValidationException> { EventStatus.parse("ARCHIVED") }
    }

    @Test
    fun `booking status knows which states are terminal`() {
        assertFalse(BookingStatus.PENDING.isTerminal)
        assertFalse(BookingStatus.CONFIRMED.isTerminal)
        assertTrue(BookingStatus.CANCELLED.isTerminal)
        assertTrue(BookingStatus.EXPIRED.isTerminal)
    }

    // ---------------- Seat labels ----------------

    @Test
    fun `seat label combines row and number`() {
        val seat = Seat(
            id = UUID.randomUUID(),
            venueId = UUID.randomUUID(),
            seatRow = "A",
            seatNumber = 12,
            seatType = SeatType.PREMIUM,
            priceMultiplier = BigDecimal("1.50"),
        )
        assertEquals("A12", seat.label)
    }

    // ---------------- Lock expiry (the heart of the hold mechanism) ----------------

    private fun eventSeat(
        status: SeatStatus,
        lockedBy: UUID? = null,
        lockExpiresAt: OffsetDateTime? = null,
    ) = EventSeat(
        id = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        seatId = UUID.randomUUID(),
        status = status,
        version = 0,
        lockedBy = lockedBy,
        lockExpiresAt = lockExpiresAt,
    )

    @Test
    fun `available seat is claimable`() {
        val seat = eventSeat(SeatStatus.AVAILABLE)
        assertEquals(SeatStatus.AVAILABLE, seat.effectiveStatus(now))
        assertTrue(seat.isClaimableBy(UUID.randomUUID(), now))
    }

    @Test
    fun `booked seat is never claimable`() {
        val seat = eventSeat(SeatStatus.BOOKED)
        assertEquals(SeatStatus.BOOKED, seat.effectiveStatus(now))
        assertFalse(seat.isClaimableBy(UUID.randomUUID(), now))
    }

    @Test
    fun `live lock held by someone else blocks the seat`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val seat = eventSeat(SeatStatus.LOCKED, owner, now.plusHours(23))

        assertFalse(seat.isLockExpiredAt(now))
        assertEquals(SeatStatus.LOCKED, seat.effectiveStatus(now))
        assertFalse(seat.isClaimableBy(other, now), "another user must not take a live hold")
        assertTrue(seat.isClaimableBy(owner, now), "the holder may act on their own hold")
    }

    @Test
    fun `expired lock reads as available even before the sweeper runs`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        // Held 24h ago with a 24h TTL: the deadline has just passed.
        val seat = eventSeat(SeatStatus.LOCKED, owner, now.minusSeconds(1))

        assertTrue(seat.isLockExpiredAt(now))
        assertEquals(
            SeatStatus.AVAILABLE,
            seat.effectiveStatus(now),
            "a lapsed hold must not keep a seat off the market",
        )
        assertTrue(seat.isClaimableBy(other, now), "anyone may claim a seat whose hold lapsed")
    }

    @Test
    fun `lock expiring exactly now is treated as expired`() {
        // Boundary: expires_at == now. Treating this as expired avoids a seat
        // being stuck for one clock tick, and matches the SQL `<= now()` sweep.
        val seat = eventSeat(SeatStatus.LOCKED, UUID.randomUUID(), now)
        assertTrue(seat.isLockExpiredAt(now))
        assertEquals(SeatStatus.AVAILABLE, seat.effectiveStatus(now))
    }

    @Test
    fun `locked seat with no expiry stays locked`() {
        // Defensive: a LOCKED row with a null deadline should not be silently freed.
        val seat = eventSeat(SeatStatus.LOCKED, UUID.randomUUID(), null)
        assertFalse(seat.isLockExpiredAt(now))
        assertEquals(SeatStatus.LOCKED, seat.effectiveStatus(now))
    }

    // ---------------- Booking hold expiry ----------------

    private fun booking(
        status: BookingStatus,
        holdExpiresAt: OffsetDateTime? = null,
    ) = Booking(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        bookingReference = "TB-ABC123",
        status = status,
        totalAmount = BigDecimal("4500.00"),
        createdAt = now.toLocalDateTime(),
        holdExpiresAt = holdExpiresAt,
    )

    @Test
    fun `pending booking within its window is an active hold`() {
        val hold = booking(BookingStatus.PENDING, now.plusHours(24))
        assertTrue(hold.isActiveHoldAt(now))
        assertFalse(hold.isHoldExpiredAt(now))
    }

    @Test
    fun `pending booking past its window is an expired hold`() {
        val hold = booking(BookingStatus.PENDING, now.minusMinutes(1))
        assertTrue(hold.isHoldExpiredAt(now))
        assertFalse(hold.isActiveHoldAt(now), "an expired hold must not be confirmable")
    }

    @Test
    fun `confirmed booking is not a hold regardless of expiry column`() {
        val confirmed = booking(BookingStatus.CONFIRMED, now.minusHours(5))
        assertFalse(confirmed.isHoldExpiredAt(now))
        assertFalse(confirmed.isActiveHoldAt(now))
    }

    // ---------------- Event rules ----------------

    private fun event(status: EventStatus, start: OffsetDateTime) = Event(
        id = UUID.randomUUID(),
        venueId = UUID.randomUUID(),
        title = "Coldplay Live",
        description = null,
        category = "Concert",
        startTime = start.toLocalDateTime(),
        endTime = start.plusHours(3).toLocalDateTime(),
        basePrice = BigDecimal("1500.00"),
        status = status,
        posterUrl = null,
    )

    @Test
    fun `only published events are public`() {
        assertTrue(event(EventStatus.PUBLISHED, now.plusDays(5)).isPublic)
        assertFalse(event(EventStatus.DRAFT, now.plusDays(5)).isPublic)
        assertFalse(event(EventStatus.CANCELLED, now.plusDays(5)).isPublic)
    }

    @Test
    fun `event start detection drives the cancellation window`() {
        val upcoming = event(EventStatus.PUBLISHED, now.plusHours(1))
        val started = event(EventStatus.PUBLISHED, now.minusHours(1))
        val startingExactlyNow = event(EventStatus.PUBLISHED, now)

        assertFalse(upcoming.hasStartedAt(now.toLocalDateTime()))
        assertTrue(started.hasStartedAt(now.toLocalDateTime()))
        // At the exact start time the window is closed - no cancelling as the show begins.
        assertTrue(startingExactlyNow.hasStartedAt(now.toLocalDateTime()))
    }
}
