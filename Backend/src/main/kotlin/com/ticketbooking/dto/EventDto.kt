package com.ticketbooking.dto

import com.ticketbooking.repository.EventWithVenue
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

/**
 * One event in a listing (spec section 6.2).
 *
 * Timestamps are ISO-8601 strings rather than numbers so the payload is readable
 * and `new Date(...)` in the browser parses them directly.
 */
@Serializable
data class EventSummaryResponse(
    val id: String,
    val title: String,
    val category: String? = null,
    val city: String,
    val venueName: String,
    val startTime: String,
    val endTime: String,
    val basePrice: String,
    val posterUrl: String? = null,
    val status: String,
)

/** Full event detail for GET /api/events/{id}. */
@Serializable
data class EventDetailResponse(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val startTime: String,
    val endTime: String,
    val basePrice: String,
    val posterUrl: String? = null,
    val status: String,
    val venue: VenueSummaryResponse,
)

/** Venue details embedded in an event response. */
@Serializable
data class VenueSummaryResponse(
    val id: String,
    val name: String,
    val city: String,
    val address: String? = null,
)

/**
 * The distinct values available for filtering, so the frontend can populate its
 * city and category dropdowns from real data instead of hard-coding them.
 */
@Serializable
data class EventFiltersResponse(
    val cities: List<String>,
    val categories: List<String>,
)

/** Shared formatter: seconds precision, no timezone suffix, e.g. "2026-10-01T19:00:00". */
private val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/**
 * Prices are rendered as plain decimal strings ("1500.00").
 *
 * Deliberately NOT a JSON number: binary floating point cannot represent every
 * decimal exactly, and money that silently rounds is a real defect. The client
 * formats the string for display and never does arithmetic on it.
 */
internal fun java.math.BigDecimal.toMoneyString(): String = this.toPlainString()

internal fun java.time.LocalDateTime.toIsoString(): String = this.format(ISO_SECONDS)

internal fun java.time.OffsetDateTime.toIsoString(): String =
    this.toInstant().toString()

/** Maps a repository row to the listing shape. */
fun EventWithVenue.toSummaryResponse() = EventSummaryResponse(
    id = event.id.toString(),
    title = event.title,
    category = event.category,
    city = venueCity,
    venueName = venueName,
    startTime = event.startTime.toIsoString(),
    endTime = event.endTime.toIsoString(),
    basePrice = event.basePrice.toMoneyString(),
    posterUrl = event.posterUrl,
    status = event.status.name,
)

/** Maps a repository row to the detail shape. */
fun EventWithVenue.toDetailResponse() = EventDetailResponse(
    id = event.id.toString(),
    title = event.title,
    description = event.description,
    category = event.category,
    startTime = event.startTime.toIsoString(),
    endTime = event.endTime.toIsoString(),
    basePrice = event.basePrice.toMoneyString(),
    posterUrl = event.posterUrl,
    status = event.status.name,
    venue = VenueSummaryResponse(
        id = event.venueId.toString(),
        name = venueName,
        city = venueCity,
        address = venueAddress,
    ),
)
