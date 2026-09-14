package com.ticketbooking.dto

import com.ticketbooking.repository.BookingDetail
import kotlinx.serialization.Serializable

/**
 * POST /api/events/{id}/hold body.
 *
 * [eventSeatIds] are the `eventSeatId` values from the seat map - per-event rows,
 * not physical seat ids.
 */
@Serializable
data class HoldRequest(
    val eventSeatIds: List<String>? = null,
)

/** One seat within a booking response. */
@Serializable
data class BookingSeatResponse(
    val eventSeatId: String,
    /** Human-readable position, e.g. "A12". */
    val label: String,
    val row: String,
    val number: Int,
    val type: String,
    val price: String,
)

/**
 * A booking or an unconfirmed hold (spec section 6.3).
 *
 * [expiresAt] is present only while [status] is PENDING - it is the instant the
 * 24-hour hold lapses and the seats return to the market.
 */
@Serializable
data class BookingResponse(
    val id: String,
    val bookingReference: String,
    val status: String,
    val totalAmount: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val event: BookingEventResponse,
    val seats: List<BookingSeatResponse>,
    /** Convenience for the UI: ["A1", "A2"], matching the spec's example payload. */
    val seatLabels: List<String>,
    /** Present only on admin listings, so bookings can be attributed to a person. */
    val user: BookingUserResponse? = null,
)

/** The event a booking belongs to, denormalised so the client needs no second call. */
@Serializable
data class BookingEventResponse(
    val id: String,
    val title: String,
    val startTime: String,
    val venueName: String,
    val venueCity: String,
)

/** Who placed a booking. Admin listings only. */
@Serializable
data class BookingUserResponse(
    val name: String,
    val email: String,
)

/** Paginated admin booking listing. */
@Serializable
data class BookingListResponse(
    val bookings: List<BookingResponse>,
    val total: Long,
    val limit: Int,
    val offset: Long,
)

/** Maps a repository row to the API shape. */
fun BookingDetail.toResponse(includeUser: Boolean = false) = BookingResponse(
    id = booking.id.toString(),
    bookingReference = booking.bookingReference,
    status = booking.status.name,
    totalAmount = booking.totalAmount.toMoneyString(),
    createdAt = booking.createdAt.toIsoString(),
    // Only meaningful while the hold is live.
    expiresAt = booking.holdExpiresAt?.toIsoString(),
    event = BookingEventResponse(
        id = booking.eventId.toString(),
        title = eventTitle,
        startTime = eventStartTime.toIsoString(),
        venueName = venueName,
        venueCity = venueCity,
    ),
    seats = seats.map { seat ->
        BookingSeatResponse(
            eventSeatId = seat.eventSeatId.toString(),
            label = seat.label,
            row = seat.seatRow,
            number = seat.seatNumber,
            type = seat.seatType.name,
            price = seat.price.toMoneyString(),
        )
    },
    seatLabels = seats.map { it.label },
    user = if (includeUser && userName != null && userEmail != null) {
        BookingUserResponse(userName, userEmail)
    } else {
        null
    },
)
