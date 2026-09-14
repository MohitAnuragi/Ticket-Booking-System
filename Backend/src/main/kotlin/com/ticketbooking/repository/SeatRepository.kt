package com.ticketbooking.repository

import com.ticketbooking.model.EventSeat
import com.ticketbooking.model.Seat
import com.ticketbooking.model.SeatType
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** One seat to create as part of a bulk layout generation. */
data class SeatSpec(
    val seatRow: String,
    val seatNumber: Int,
    val seatType: SeatType,
    val priceMultiplier: BigDecimal,
)

/**
 * An `event_seats` row joined to its physical `seats` row.
 *
 * The seat map endpoint needs both halves - status comes from the event seat,
 * position and price multiplier from the physical seat.
 */
data class EventSeatWithSeat(
    val eventSeat: EventSeat,
    val seat: Seat,
)

/**
 * Data access for `seats` (the venue's fixed layout) and `event_seats`
 * (per-event availability).
 *
 * Both live in one repository because they are always used together: a seat map
 * is meaningless without positions, and generating event seats is driven by the
 * venue's seat rows.
 */
interface SeatRepository {

    // ---------------- Physical seats (venue layout) ----------------

    fun findSeatsByVenue(venueId: UUID): List<Seat>

    fun countSeatsByVenue(venueId: UUID): Long

    /**
     * Row/number labels that already exist for this venue, so layout generation
     * can reject a request that would collide with the UNIQUE(venue_id, seat_row,
     * seat_number) constraint and report which ones clash.
     */
    fun existingSeatLabels(venueId: UUID): Set<String>

    /**
     * Bulk-inserts a seat layout.
     * @return the created seats.
     */
    fun createSeats(venueId: UUID, specs: List<SeatSpec>): List<Seat>

    // ---------------- Per-event seats ----------------

    /**
     * Creates one `event_seats` row per seat id, all AVAILABLE with version 0.
     * @return number of rows created.
     */
    fun createEventSeats(eventId: UUID, seatIds: List<UUID>): Int

    /** Full seat map for an event, ordered by row then seat number. */
    fun findEventSeatMap(eventId: UUID): List<EventSeatWithSeat>

    fun countEventSeats(eventId: UUID): Long

    fun findEventSeatById(eventSeatId: UUID): EventSeat?

    /**
     * Loads the given event seats with `SELECT ... FOR UPDATE`, taking a row-level
     * lock on each.
     *
     * MUST be called inside a [TransactionRunner.inTransaction] block: the locks
     * are held until that transaction commits or rolls back, which is precisely
     * what serialises two users racing for the same seat.
     *
     * Rows are locked in a deterministic order (by id) to avoid deadlocks between
     * two transactions requesting overlapping seat sets in different orders.
     *
     * @return the locked rows; ids that do not exist are simply absent.
     */
    fun lockEventSeatsForUpdate(eventId: UUID, eventSeatIds: List<UUID>): List<EventSeatWithSeat>

    /**
     * Marks seats LOCKED for [lockedBy] until [expiresAt] and increments `version`.
     * @return number of rows updated.
     */
    fun markLocked(
        eventSeatIds: List<UUID>,
        lockedBy: UUID,
        expiresAt: OffsetDateTime,
    ): Int

    /** Marks seats BOOKED and clears the lock fields. */
    fun markBooked(eventSeatIds: List<UUID>): Int

    /** Returns seats to AVAILABLE and clears the lock fields. */
    fun markAvailable(eventSeatIds: List<UUID>): Int

    /**
     * Sweeper support: returns every LOCKED seat whose `lock_expires_at` is at or
     * before [now] to AVAILABLE.
     *
     * @return number of seats released.
     */
    fun releaseExpiredLocks(now: OffsetDateTime): Int
}
