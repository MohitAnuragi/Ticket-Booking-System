package com.ticketbooking.repository

import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Query object for `GET /events`. Every field is optional; null means "no
 * constraint on this dimension".
 *
 * Passing filters as one object rather than a long parameter list keeps the
 * repository signature stable as new filters are added.
 */
data class EventFilter(
    /** Free-text match against title and description, case-insensitive. */
    val search: String? = null,
    val city: String? = null,
    val category: String? = null,
    /** Matches events whose start_time falls on this calendar date (UTC). */
    val date: java.time.LocalDate? = null,
    /**
     * Restricts results by status. Public endpoints pass PUBLISHED only; admin
     * endpoints pass null to see drafts too.
     */
    val statuses: Set<EventStatus>? = null,
    /** When true, hides events whose start_time is in the past. */
    val upcomingOnly: Boolean = false,
)

/**
 * An event together with the venue fields the listing needs, so a list response
 * does not require a second query per row.
 */
data class EventWithVenue(
    val event: Event,
    val venueName: String,
    val venueCity: String,
    val venueAddress: String?,
)

/** Data access for `events`. */
interface EventRepository {

    fun findById(id: UUID): Event?

    /** Event plus its venue details, for the detail endpoint. */
    fun findByIdWithVenue(id: UUID): EventWithVenue?

    /** Applies [filter], ordered by start_time ascending. */
    fun search(filter: EventFilter): List<EventWithVenue>

    fun create(
        id: UUID,
        venueId: UUID,
        title: String,
        description: String?,
        category: String?,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        basePrice: BigDecimal,
        status: EventStatus,
        posterUrl: String?,
    ): Event

    /**
     * Updates the mutable fields of an event. Null arguments leave the existing
     * value untouched, so callers can patch a single field.
     *
     * @return the updated event, or null if no event has this id.
     */
    fun update(
        id: UUID,
        title: String? = null,
        description: String? = null,
        category: String? = null,
        startTime: LocalDateTime? = null,
        endTime: LocalDateTime? = null,
        basePrice: BigDecimal? = null,
        status: EventStatus? = null,
        posterUrl: String? = null,
    ): Event?

    fun updateStatus(id: UUID, status: EventStatus): Boolean

    /** Distinct non-null categories, to populate the frontend filter dropdown. */
    fun distinctCategories(): List<String>

    /** Distinct cities of venues that host at least one event. */
    fun distinctCities(): List<String>

    fun countAll(): Long
}
