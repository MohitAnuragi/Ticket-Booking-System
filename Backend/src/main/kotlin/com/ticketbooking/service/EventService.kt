package com.ticketbooking.service

import com.ticketbooking.dto.EventDetailResponse
import com.ticketbooking.dto.EventFiltersResponse
import com.ticketbooking.dto.EventSummaryResponse
import com.ticketbooking.dto.toDetailResponse
import com.ticketbooking.dto.toSummaryResponse
import com.ticketbooking.model.EventStatus
import com.ticketbooking.repository.EventFilter
import com.ticketbooking.repository.EventRepository
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.ValidationException
import com.ticketbooking.util.Validators
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Public event browsing.
 *
 * The visibility rule lives here rather than in the controller or the repository:
 * anonymous callers only ever see PUBLISHED events, so an unfinished DRAFT cannot
 * leak just because a client guessed a URL.
 */
class EventService(
    private val events: EventRepository,
    private val transactions: TransactionRunner,
    private val time: TimeProvider = TimeProvider.SYSTEM,
) {

    /**
     * Search and filter, for GET /api/events.
     *
     * All parameters are optional. Blank strings are treated as absent so that a
     * form submitted with empty inputs behaves like no filter at all rather than
     * matching nothing.
     *
     * @param date ISO date (yyyy-MM-dd); matches events starting on that day.
     * @param includeUnpublished admin-only. Never set from a public route.
     */
    fun listEvents(
        search: String? = null,
        city: String? = null,
        category: String? = null,
        date: String? = null,
        upcomingOnly: Boolean = false,
        includeUnpublished: Boolean = false,
    ): List<EventSummaryResponse> {
        val filter = EventFilter(
            search = search?.trim()?.takeIf { it.isNotEmpty() },
            city = city?.trim()?.takeIf { it.isNotEmpty() },
            category = category?.trim()?.takeIf { it.isNotEmpty() },
            date = parseDate(date),
            statuses = if (includeUnpublished) null else setOf(EventStatus.PUBLISHED),
            upcomingOnly = upcomingOnly,
        )

        return transactions.inTransaction { events.search(filter) }
            .map { it.toSummaryResponse() }
    }

    /**
     * A single event, for GET /api/events/{id}.
     *
     * A DRAFT event returns 404 rather than 403 for public callers: revealing that
     * an id exists but is hidden is itself an information leak.
     *
     * @throws ValidationException if [rawId] is not a UUID.
     * @throws NotFoundException if no such event is visible to this caller.
     */
    fun getEvent(rawId: String?, includeUnpublished: Boolean = false): EventDetailResponse {
        val id = Validators.parseUuid(rawId, "id")
        return getEvent(id, includeUnpublished)
    }

    fun getEvent(id: UUID, includeUnpublished: Boolean = false): EventDetailResponse {
        val found = transactions.inTransaction { events.findByIdWithVenue(id) }
            ?: throw NotFoundException("Event not found")

        if (!includeUnpublished && !found.event.isPublic) {
            throw NotFoundException("Event not found")
        }
        return found.toDetailResponse()
    }

    /** Distinct cities and categories for the frontend's filter dropdowns. */
    fun listFilterOptions(): EventFiltersResponse = transactions.inTransaction {
        EventFiltersResponse(
            cities = events.distinctCities(),
            categories = events.distinctCategories(),
        )
    }

    /**
     * Parses the `date` query parameter.
     *
     * @return null when absent or blank.
     * @throws ValidationException on an unparseable value, so a typo produces a
     *         clear 400 rather than silently returning every event.
     */
    private fun parseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw ValidationException("date must be in yyyy-MM-dd format", "date")
        }
    }
}
