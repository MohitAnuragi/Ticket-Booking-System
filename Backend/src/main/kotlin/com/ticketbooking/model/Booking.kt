package com.ticketbooking.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Booking lifecycle.
 *
 * A hold IS a booking in [PENDING] state - reusing the status the schema already
 * defines, rather than adding a separate holds table, means holds inherit the same
 * pricing, seat linkage and history as confirmed bookings.
 *
 *   PENDING   --confirm--> CONFIRMED
 *   PENDING   --release/sweep--> EXPIRED
 *   CONFIRMED --cancel--> CANCELLED
 *
 * [EXPIRED] is not in the base schema's comment but the column is a plain
 * VARCHAR(20) with no CHECK constraint, so it stores cleanly. Keeping it distinct
 * from [CANCELLED] preserves the difference between "the user changed their mind"
 * and "the hold lapsed".
 */
enum class BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    ;

    /** Terminal states cannot transition any further. */
    val isTerminal: Boolean get() = this == CANCELLED || this == EXPIRED

    companion object {
        fun from(raw: String): BookingStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PENDING

        fun parseOrNull(raw: String): BookingStatus? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * `bookings` table.
 *
 * `hold_expires_at` is added by db/02_deltas.sql and is only set while the
 * booking is [BookingStatus.PENDING].
 */
object Bookings : Table("bookings") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(Users.id)
    val eventId = javaUUID("event_id").references(Events.id)
    val bookingReference = varchar("booking_reference", 20)
    val status = varchar("status", 20)
    val totalAmount = decimal("total_amount", 10, 2)
    val createdAt = datetime("created_at")
    val holdExpiresAt = timestampWithTimeZone("hold_expires_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A booking, which may still be an unconfirmed hold. */
data class Booking(
    val id: UUID,
    val userId: UUID,
    val eventId: UUID,
    val bookingReference: String,
    val status: BookingStatus,
    val totalAmount: BigDecimal,
    val createdAt: LocalDateTime,
    val holdExpiresAt: OffsetDateTime?,
) {
    /** True for a hold whose deadline has passed but which has not been swept yet. */
    fun isHoldExpiredAt(now: OffsetDateTime): Boolean =
        status == BookingStatus.PENDING && holdExpiresAt != null && !holdExpiresAt.isAfter(now)

    /** A hold that can still be confirmed. */
    fun isActiveHoldAt(now: OffsetDateTime): Boolean =
        status == BookingStatus.PENDING && !isHoldExpiredAt(now)
}
