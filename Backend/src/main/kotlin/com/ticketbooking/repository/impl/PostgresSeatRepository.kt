package com.ticketbooking.repository.impl

import com.ticketbooking.model.EventSeat
import com.ticketbooking.model.EventSeats
import com.ticketbooking.model.Seat
import com.ticketbooking.model.SeatStatus
import com.ticketbooking.model.SeatType
import com.ticketbooking.model.Seats
import com.ticketbooking.repository.EventSeatWithSeat
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.SeatSpec
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PostgreSQL implementation of [SeatRepository].
 *
 * All methods require an active transaction. [lockEventSeatsForUpdate] in
 * particular is meaningless outside one, since the row locks it takes are released
 * when the transaction ends.
 */
class PostgresSeatRepository : SeatRepository {

    // ---------------- Physical seats ----------------

    override fun findSeatsByVenue(venueId: UUID): List<Seat> =
        Seats.selectAll()
            .where { Seats.venueId eq venueId }
            .orderBy(Seats.seatRow to SortOrder.ASC, Seats.seatNumber to SortOrder.ASC)
            .map { it.toSeat() }

    override fun countSeatsByVenue(venueId: UUID): Long =
        Seats.selectAll().where { Seats.venueId eq venueId }.count()

    override fun existingSeatLabels(venueId: UUID): Set<String> =
        Seats.selectAll()
            .where { Seats.venueId eq venueId }
            .mapTo(mutableSetOf()) { "${it[Seats.seatRow]}${it[Seats.seatNumber]}" }

    override fun createSeats(venueId: UUID, specs: List<SeatSpec>): List<Seat> {
        if (specs.isEmpty()) return emptyList()

        val created = specs.map { spec ->
            Seat(
                id = UUID.randomUUID(),
                venueId = venueId,
                seatRow = spec.seatRow,
                seatNumber = spec.seatNumber,
                seatType = spec.seatType,
                priceMultiplier = spec.priceMultiplier,
            )
        }

        // One multi-row INSERT rather than N round trips: a 30x40 layout is 1200 rows.
        Seats.batchInsert(created, shouldReturnGeneratedValues = false) { seat ->
            this[Seats.id] = seat.id
            this[Seats.venueId] = seat.venueId
            this[Seats.seatRow] = seat.seatRow
            this[Seats.seatNumber] = seat.seatNumber
            this[Seats.seatType] = seat.seatType.name
            this[Seats.priceMultiplier] = seat.priceMultiplier
        }

        return created
    }

    // ---------------- Per-event seats ----------------

    override fun createEventSeats(eventId: UUID, seatIds: List<UUID>): Int {
        if (seatIds.isEmpty()) return 0

        EventSeats.batchInsert(seatIds, shouldReturnGeneratedValues = false) { seatId ->
            this[EventSeats.id] = UUID.randomUUID()
            this[EventSeats.eventId] = eventId
            this[EventSeats.seatId] = seatId
            this[EventSeats.status] = SeatStatus.AVAILABLE.name
            this[EventSeats.version] = 0
        }
        return seatIds.size
    }

    override fun findEventSeatMap(eventId: UUID): List<EventSeatWithSeat> =
        (EventSeats innerJoin Seats)
            .selectAll()
            .where { EventSeats.eventId eq eventId }
            .orderBy(Seats.seatRow to SortOrder.ASC, Seats.seatNumber to SortOrder.ASC)
            .map { it.toEventSeatWithSeat() }

    override fun countEventSeats(eventId: UUID): Long =
        EventSeats.selectAll().where { EventSeats.eventId eq eventId }.count()

    override fun findEventSeatById(eventSeatId: UUID): EventSeat? =
        EventSeats.selectAll()
            .where { EventSeats.id eq eventSeatId }
            .limit(1)
            .singleOrNull()
            ?.toEventSeat()

    /**
     * The row-locking read at the centre of double-booking prevention.
     *
     * `forUpdate()` emits `SELECT ... FOR UPDATE`, so a second transaction asking
     * for any of the same rows BLOCKS here until this transaction commits or rolls
     * back. By the time this returns, the statuses observed cannot change underneath
     * us for the rest of the transaction.
     *
     * Ordering by id is not cosmetic: two transactions requesting overlapping seat
     * sets in different orders could otherwise deadlock, each holding a row the
     * other wants. A consistent lock order makes that impossible.
     */
    override fun lockEventSeatsForUpdate(
        eventId: UUID,
        eventSeatIds: List<UUID>,
    ): List<EventSeatWithSeat> {
        if (eventSeatIds.isEmpty()) return emptyList()

        val lockedIds = EventSeats.selectAll()
            .where { (EventSeats.eventId eq eventId) and (EventSeats.id inList eventSeatIds) }
            .orderBy(EventSeats.id to SortOrder.ASC)
            .forUpdate()
            .map { it[EventSeats.id] }

        if (lockedIds.isEmpty()) return emptyList()

        // Re-read joined to seats for position and price multiplier. Safe to do in a
        // second statement: the rows are already locked by this transaction.
        return (EventSeats innerJoin Seats)
            .selectAll()
            .where { EventSeats.id inList lockedIds }
            .orderBy(Seats.seatRow to SortOrder.ASC, Seats.seatNumber to SortOrder.ASC)
            .map { it.toEventSeatWithSeat() }
    }

    override fun markLocked(
        eventSeatIds: List<UUID>,
        lockedBy: UUID,
        expiresAt: OffsetDateTime,
    ): Int {
        if (eventSeatIds.isEmpty()) return 0
        return EventSeats.update({ EventSeats.id inList eventSeatIds }) {
            it[EventSeats.status] = SeatStatus.LOCKED.name
            it[EventSeats.lockedBy] = lockedBy
            it[EventSeats.lockExpiresAt] = expiresAt
            // Optimistic-locking counter; also a useful audit of how often a seat
            // has changed hands.
            it[EventSeats.version] = EventSeats.version + 1
        }
    }

    override fun markBooked(eventSeatIds: List<UUID>): Int {
        if (eventSeatIds.isEmpty()) return 0
        return EventSeats.update({ EventSeats.id inList eventSeatIds }) {
            it[EventSeats.status] = SeatStatus.BOOKED.name
            // A booked seat has no hold, so the lock fields must be cleared or a
            // stale deadline could later be misread as an expired hold.
            it[EventSeats.lockedBy] = null
            it[EventSeats.lockExpiresAt] = null
            it[EventSeats.version] = EventSeats.version + 1
        }
    }

    override fun markAvailable(eventSeatIds: List<UUID>): Int {
        if (eventSeatIds.isEmpty()) return 0
        return EventSeats.update({ EventSeats.id inList eventSeatIds }) {
            it[EventSeats.status] = SeatStatus.AVAILABLE.name
            it[EventSeats.lockedBy] = null
            it[EventSeats.lockExpiresAt] = null
            it[EventSeats.version] = EventSeats.version + 1
        }
    }

    /**
     * Bulk release of lapsed holds - the eager half of the expiry strategy.
     *
     * `<=` matches [com.ticketbooking.model.EventSeat.isLockExpiredAt], so the
     * sweeper and the lazy read agree on the boundary case of expires_at == now.
     */
    override fun releaseExpiredLocks(now: OffsetDateTime): Int =
        EventSeats.update({
            (EventSeats.status eq SeatStatus.LOCKED.name) and
                (EventSeats.lockExpiresAt lessEq now)
        }) {
            it[EventSeats.status] = SeatStatus.AVAILABLE.name
            it[EventSeats.lockedBy] = null
            it[EventSeats.lockExpiresAt] = null
            it[EventSeats.version] = EventSeats.version + 1
        }

    // ---------------- Mapping ----------------

    private fun ResultRow.toSeat() = Seat(
        id = this[Seats.id],
        venueId = this[Seats.venueId],
        seatRow = this[Seats.seatRow],
        seatNumber = this[Seats.seatNumber],
        seatType = SeatType.from(this[Seats.seatType]),
        priceMultiplier = this[Seats.priceMultiplier],
    )

    private fun ResultRow.toEventSeat() = EventSeat(
        id = this[EventSeats.id],
        eventId = this[EventSeats.eventId],
        seatId = this[EventSeats.seatId],
        status = SeatStatus.from(this[EventSeats.status]),
        version = this[EventSeats.version],
        lockedBy = this[EventSeats.lockedBy],
        lockExpiresAt = this[EventSeats.lockExpiresAt],
    )

    private fun ResultRow.toEventSeatWithSeat() = EventSeatWithSeat(
        eventSeat = toEventSeat(),
        seat = toSeat(),
    )
}
