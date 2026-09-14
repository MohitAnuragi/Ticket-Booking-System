package com.ticketbooking.dto

import com.ticketbooking.model.Venue
import kotlinx.serialization.Serializable

/** POST /api/admin/venues body. */
@Serializable
data class CreateVenueRequest(
    val name: String? = null,
    val address: String? = null,
    val city: String? = null,
    /**
     * Declared capacity. Optional: if omitted it is derived from the seat layout
     * once seats are generated, which avoids an admin having to count seats.
     */
    val totalCapacity: Int? = null,
)

/**
 * POST /api/admin/venues/{id}/seats body (spec section 6.4).
 *
 * Expands to `rows.size x seatsPerRow` seats, e.g. rows ["A","B","C"] with
 * seatsPerRow 10 creates A1..A10, B1..B10, C1..C10.
 */
@Serializable
data class SeatLayoutRequest(
    val rows: List<String>? = null,
    val seatsPerRow: Int? = null,
    /** REGULAR | PREMIUM | VIP. Defaults to REGULAR. */
    val seatType: String? = null,
    /**
     * Price multiplier for these seats. When omitted, a tier-appropriate default is
     * used (REGULAR 1.00, PREMIUM 1.50, VIP 2.50) so a simple request still
     * produces sensible pricing.
     */
    val priceMultiplier: String? = null,
)

/** A venue as returned by the admin endpoints. */
@Serializable
data class VenueResponse(
    val id: String,
    val name: String,
    val address: String? = null,
    val city: String,
    val totalCapacity: Int,
    /** Actual number of `seats` rows, which may differ from the declared capacity. */
    val seatCount: Long,
)

/** Result of a bulk seat layout generation. */
@Serializable
data class SeatLayoutResponse(
    val venueId: String,
    val seatsCreated: Int,
    val totalSeats: Long,
    /** e.g. ["A1", "A2", ... ] - a preview, capped so the response stays small. */
    val createdSeatLabels: List<String>,
)

fun Venue.toResponse(seatCount: Long) = VenueResponse(
    id = id.toString(),
    name = name,
    address = address,
    city = city,
    totalCapacity = totalCapacity,
    seatCount = seatCount,
)

/**
 * POST /api/admin/events body.
 *
 * Times are ISO-8601 without a zone ("2026-10-01T19:00:00") and are interpreted as
 * UTC, matching how the timestamp columns are read and written everywhere else.
 *
 * [basePrice] is a decimal STRING rather than a JSON number so no precision is lost
 * in transit - the same reason prices are returned as strings.
 */
@Serializable
data class CreateEventRequest(
    val venueId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val basePrice: String? = null,
    /** DRAFT | PUBLISHED. Defaults to PUBLISHED. */
    val status: String? = null,
    val posterUrl: String? = null,
)

/**
 * PUT /api/admin/events/{id} body.
 *
 * Every field is optional: omitted fields keep their current value, so a caller can
 * change just the price without resending the whole event. The venue is deliberately
 * NOT changeable - the event's seat rows are derived from it, and moving an event to
 * another venue would invalidate every existing booking.
 */
@Serializable
data class UpdateEventRequest(
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val basePrice: String? = null,
    val status: String? = null,
    val posterUrl: String? = null,
)

/** Result of creating an event, including how many seat rows were generated. */
@Serializable
data class CreateEventResponse(
    val event: EventDetailResponse,
    /** One `event_seats` row per venue seat, all AVAILABLE. */
    val seatsGenerated: Int,
)
