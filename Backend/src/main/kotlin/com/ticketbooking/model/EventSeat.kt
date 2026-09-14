package com.ticketbooking.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.util.UUID

/** Availability of one seat for one event. */
enum class SeatStatus {
    /** Sellable. */
    AVAILABLE,

    /** Held by a pending booking until `lock_expires_at`. */
    LOCKED,

    /** Sold. */
    BOOKED,
    ;

    companion object {
        fun from(raw: String): SeatStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AVAILABLE
    }
}

/**
 * `event_seats` table - per-event availability for a physical [Seats] row.
 *
 * `locked_by` and `lock_expires_at` are added by db/02_deltas.sql. They are
 * TIMESTAMPTZ rather than TIMESTAMP because a 24-hour deadline compared against
 * `now()` in a timezone-naive column would expire at the wrong moment.
 *
 * `version` supports optimistic locking. The hard guarantee against double
 * booking is the partial unique index on `booking_seats`, with `SELECT ... FOR
 * UPDATE` on these rows serialising concurrent claims.
 */
object EventSeats : Table("event_seats") {
    val id = javaUUID("id")
    val eventId = javaUUID("event_id").references(Events.id)
    val seatId = javaUUID("seat_id").references(Seats.id)
    val status = varchar("status", 20)
    val version = integer("version")
    val lockedBy = javaUUID("locked_by").references(Users.id).nullable()
    val lockExpiresAt = timestampWithTimeZone("lock_expires_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId, seatId)
    }
}

/**
 * A seat's state for one event.
 *
 * [effectiveStatus] is the value the rest of the system should trust: it reports
 * an expired hold as [SeatStatus.AVAILABLE] even before the background sweeper
 * has rewritten the row. This is the "lazy" half of the two-part expiry strategy
 * and it means a lapsed hold can never block a sale, even if the sweeper is down.
 */
data class EventSeat(
    val id: UUID,
    val eventId: UUID,
    val seatId: UUID,
    val status: SeatStatus,
    val version: Int,
    val lockedBy: UUID?,
    val lockExpiresAt: OffsetDateTime?,
) {
    /** True when this row is LOCKED but its hold deadline has already passed. */
    fun isLockExpiredAt(now: OffsetDateTime): Boolean =
        status == SeatStatus.LOCKED && lockExpiresAt != null && !lockExpiresAt.isAfter(now)

    /** Availability accounting for lapsed holds. */
    fun effectiveStatus(now: OffsetDateTime): SeatStatus =
        if (isLockExpiredAt(now)) SeatStatus.AVAILABLE else status

    /** Whether this seat can be claimed right now, optionally by the current holder. */
    fun isClaimableBy(userId: UUID?, now: OffsetDateTime): Boolean = when (effectiveStatus(now)) {
        SeatStatus.AVAILABLE -> true
        // A user extending or re-submitting their own live hold is not a conflict.
        SeatStatus.LOCKED -> userId != null && lockedBy == userId
        SeatStatus.BOOKED -> false
    }
}
