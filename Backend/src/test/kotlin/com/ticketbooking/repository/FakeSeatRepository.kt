package com.ticketbooking.repository

import com.ticketbooking.model.EventSeat
import com.ticketbooking.model.Seat
import com.ticketbooking.model.SeatStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * In-memory [SeatRepository] for unit tests.
 *
 * [lockEventSeatsForUpdate] here does NOT take real locks - it cannot, there is no
 * database. It returns the same rows the SQL version would so the surrounding
 * business rules (which seats are claimable, what the total costs) can be tested.
 * The actual row-locking behaviour is a PostgreSQL guarantee and is verified by
 * hand with two concurrent clients, as agreed for this build.
 */
class FakeSeatRepository : SeatRepository {

    private val seats = linkedMapOf<UUID, Seat>()
    private val eventSeats = linkedMapOf<UUID, EventSeat>()

    /** Records the order lock requests arrived in, for assertions about batching. */
    val lockCalls = mutableListOf<List<UUID>>()

    // ---------------- test setup helpers ----------------

    fun addSeat(seat: Seat) {
        seats[seat.id] = seat
    }

    fun addEventSeat(eventSeat: EventSeat) {
        eventSeats[eventSeat.id] = eventSeat
    }

    /** Convenience: creates a physical seat plus its per-event row. */
    fun addAvailableSeat(
        eventId: UUID,
        venueId: UUID = UUID.randomUUID(),
        row: String = "A",
        number: Int = 1,
        seatType: com.ticketbooking.model.SeatType = com.ticketbooking.model.SeatType.REGULAR,
        multiplier: java.math.BigDecimal = java.math.BigDecimal("1.00"),
        status: SeatStatus = SeatStatus.AVAILABLE,
        lockedBy: UUID? = null,
        lockExpiresAt: OffsetDateTime? = null,
    ): EventSeat {
        val seat = Seat(UUID.randomUUID(), venueId, row, number, seatType, multiplier)
        seats[seat.id] = seat
        val eventSeat = EventSeat(
            id = UUID.randomUUID(),
            eventId = eventId,
            seatId = seat.id,
            status = status,
            version = 0,
            lockedBy = lockedBy,
            lockExpiresAt = lockExpiresAt,
        )
        eventSeats[eventSeat.id] = eventSeat
        return eventSeat
    }

    fun eventSeat(id: UUID): EventSeat? = eventSeats[id]

    // ---------------- SeatRepository ----------------

    override fun findSeatsByVenue(venueId: UUID): List<Seat> =
        seats.values.filter { it.venueId == venueId }
            .sortedWith(compareBy({ it.seatRow }, { it.seatNumber }))

    override fun countSeatsByVenue(venueId: UUID): Long =
        seats.values.count { it.venueId == venueId }.toLong()

    override fun existingSeatLabels(venueId: UUID): Set<String> =
        seats.values.filter { it.venueId == venueId }.mapTo(mutableSetOf()) { it.label }

    override fun createSeats(venueId: UUID, specs: List<SeatSpec>): List<Seat> {
        val created = specs.map { spec ->
            Seat(UUID.randomUUID(), venueId, spec.seatRow, spec.seatNumber, spec.seatType, spec.priceMultiplier)
        }
        // Mirrors UNIQUE(venue_id, seat_row, seat_number).
        created.forEach { seat ->
            require(seats.values.none { it.venueId == venueId && it.label == seat.label }) {
                "duplicate seat ${seat.label}"
            }
            seats[seat.id] = seat
        }
        return created
    }

    override fun createEventSeats(eventId: UUID, seatIds: List<UUID>): Int {
        seatIds.forEach { seatId ->
            val eventSeat = EventSeat(
                id = UUID.randomUUID(),
                eventId = eventId,
                seatId = seatId,
                status = SeatStatus.AVAILABLE,
                version = 0,
                lockedBy = null,
                lockExpiresAt = null,
            )
            eventSeats[eventSeat.id] = eventSeat
        }
        return seatIds.size
    }

    override fun findEventSeatMap(eventId: UUID): List<EventSeatWithSeat> =
        eventSeats.values
            .filter { it.eventId == eventId }
            .mapNotNull { es -> seats[es.seatId]?.let { EventSeatWithSeat(es, it) } }
            .sortedWith(compareBy({ it.seat.seatRow }, { it.seat.seatNumber }))

    override fun countEventSeats(eventId: UUID): Long =
        eventSeats.values.count { it.eventId == eventId }.toLong()

    override fun findEventSeatById(eventSeatId: UUID): EventSeat? = eventSeats[eventSeatId]

    override fun lockEventSeatsForUpdate(
        eventId: UUID,
        eventSeatIds: List<UUID>,
    ): List<EventSeatWithSeat> {
        lockCalls += eventSeatIds
        return eventSeatIds
            .mapNotNull { eventSeats[it] }
            .filter { it.eventId == eventId }
            .mapNotNull { es -> seats[es.seatId]?.let { EventSeatWithSeat(es, it) } }
            .sortedWith(compareBy({ it.seat.seatRow }, { it.seat.seatNumber }))
    }

    override fun markLocked(
        eventSeatIds: List<UUID>,
        lockedBy: UUID,
        expiresAt: OffsetDateTime,
    ): Int = mutate(eventSeatIds) {
        it.copy(
            status = SeatStatus.LOCKED,
            lockedBy = lockedBy,
            lockExpiresAt = expiresAt,
            version = it.version + 1,
        )
    }

    override fun markBooked(eventSeatIds: List<UUID>): Int = mutate(eventSeatIds) {
        it.copy(
            status = SeatStatus.BOOKED,
            lockedBy = null,
            lockExpiresAt = null,
            version = it.version + 1,
        )
    }

    override fun markAvailable(eventSeatIds: List<UUID>): Int = mutate(eventSeatIds) {
        it.copy(
            status = SeatStatus.AVAILABLE,
            lockedBy = null,
            lockExpiresAt = null,
            version = it.version + 1,
        )
    }

    override fun releaseExpiredLocks(now: OffsetDateTime): Int {
        val expired = eventSeats.values
            .filter { it.status == SeatStatus.LOCKED && it.lockExpiresAt?.isAfter(now) == false }
            .map { it.id }
        return markAvailable(expired)
    }

    private fun mutate(ids: List<UUID>, transform: (EventSeat) -> EventSeat): Int {
        var changed = 0
        ids.forEach { id ->
            eventSeats[id]?.let { existing ->
                eventSeats[id] = transform(existing)
                changed++
            }
        }
        return changed
    }
}
