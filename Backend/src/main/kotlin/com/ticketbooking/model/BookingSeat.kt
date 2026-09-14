package com.ticketbooking.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.math.BigDecimal
import java.util.UUID

/**
 * `booking_seats` table - the seats belonging to a booking, with the price actually
 * charged for each (captured at booking time so later price changes cannot rewrite
 * history).
 *
 * THIS TABLE HOLDS THE DOUBLE-BOOKING GUARANTEE.
 *
 * db/02_deltas.sql replaces the base schema's plain `UNIQUE (event_seat_id)` with:
 *
 *     CREATE UNIQUE INDEX uq_booking_seats_active
 *         ON booking_seats (event_seat_id) WHERE is_active;
 *
 * So at most ONE active claim can exist per event seat, enforced by PostgreSQL
 * rather than by application logic. Two concurrent transactions claiming the same
 * seat end with one committed and one rejected. Cancelling flips [isActive] to
 * false, which frees the seat to be sold again while keeping the historical row.
 */
object BookingSeats : Table("booking_seats") {
    val id = javaUUID("id")
    val bookingId = javaUUID("booking_id").references(Bookings.id)
    val eventSeatId = javaUUID("event_seat_id").references(EventSeats.id)
    val price = decimal("price", 10, 2)
    val isActive = bool("is_active")

    override val primaryKey = PrimaryKey(id)
}

/** One seat within a booking, at the price charged for it. */
data class BookingSeat(
    val id: UUID,
    val bookingId: UUID,
    val eventSeatId: UUID,
    val price: BigDecimal,
    val isActive: Boolean,
)

/**
 * A booked seat enriched with its physical position, for responses that need to
 * show "A12" rather than an opaque id.
 */
data class BookingSeatDetail(
    val eventSeatId: UUID,
    val seatRow: String,
    val seatNumber: Int,
    val seatType: SeatType,
    val price: BigDecimal,
) {
    val label: String get() = "$seatRow$seatNumber"
}
