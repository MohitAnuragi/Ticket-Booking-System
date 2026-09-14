package com.ticketbooking.model

import com.ticketbooking.util.ValidationException
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.math.BigDecimal
import java.util.UUID

/**
 * Seat tiers. [priceMultiplier] on a seat scales the event's base price, which is
 * how one event can sell the same physical room at several price points.
 */
enum class SeatType {
    REGULAR,
    PREMIUM,
    VIP,
    ;

    companion object {
        /** Strict parse for request payloads - an unknown tier is a client error. */
        fun parse(raw: String, field: String = "seatType"): SeatType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "seatType must be one of ${entries.joinToString(", ") { it.name }}",
                    field,
                )

        /** Lenient parse for values already in the database. */
        fun from(raw: String): SeatType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: REGULAR
    }
}

/**
 * `seats` table - the venue's FIXED physical layout, created once per venue.
 *
 * Per-event availability lives in [EventSeats], not here. That separation is what
 * lets the same physical seat be free for one event and sold for another without
 * duplicating the layout.
 */
object Seats : Table("seats") {
    val id = javaUUID("id")
    val venueId = javaUUID("venue_id").references(Venues.id)
    val seatRow = varchar("seat_row", 5)
    val seatNumber = integer("seat_number")
    val seatType = varchar("seat_type", 20)
    val priceMultiplier = decimal("price_multiplier", 4, 2)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(venueId, seatRow, seatNumber)
    }
}

/** One physical seat in a venue, e.g. row "A" seat 12, PREMIUM, 1.50x base price. */
data class Seat(
    val id: UUID,
    val venueId: UUID,
    val seatRow: String,
    val seatNumber: Int,
    val seatType: SeatType,
    val priceMultiplier: BigDecimal,
) {
    /** Human-readable label used in booking confirmations, e.g. "A12". */
    val label: String get() = "$seatRow$seatNumber"
}
