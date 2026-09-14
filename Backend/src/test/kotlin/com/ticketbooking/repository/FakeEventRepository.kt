package com.ticketbooking.repository

import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * In-memory [EventRepository] for unit tests.
 *
 * The filter logic here mirrors [com.ticketbooking.repository.impl.PostgresEventRepository]'s
 * SQL: case-insensitive city/category matching, substring search across title and
 * description, and a whole-day window for the date filter. Keeping the two in step
 * is what makes these tests meaningful rather than self-fulfilling.
 */
class FakeEventRepository(
    initial: List<EventWithVenue> = emptyList(),
) : EventRepository {

    private val store = linkedMapOf<UUID, EventWithVenue>()

    /** Set to a fixed value so `upcomingOnly` is deterministic in tests. */
    var now: LocalDateTime = LocalDateTime.of(2026, 9, 11, 12, 0)

    /**
     * Resolves venue details for newly created events.
     *
     * The real repository joins `venues`, so without this a created event would
     * report placeholder venue details and tests could pass against behaviour the
     * production code does not have. Point this at a [FakeVenueRepository] to get
     * faithful results.
     */
    var venueResolver: ((UUID) -> com.ticketbooking.model.Venue?)? = null

    init {
        initial.forEach { store[it.event.id] = it }
    }

    override fun findById(id: UUID): Event? = store[id]?.event

    override fun findByIdWithVenue(id: UUID): EventWithVenue? = store[id]

    override fun search(filter: EventFilter): List<EventWithVenue> =
        store.values
            .filter { row -> matches(row, filter) }
            .sortedBy { it.event.startTime }

    private fun matches(row: EventWithVenue, filter: EventFilter): Boolean {
        val event = row.event

        filter.statuses?.let { statuses ->
            if (statuses.isNotEmpty() && event.status !in statuses) return false
        }

        filter.city?.let { city ->
            if (!row.venueCity.equals(city, ignoreCase = true)) return false
        }

        filter.category?.let { category ->
            if (!(event.category ?: "").equals(category, ignoreCase = true)) return false
        }

        filter.search?.let { term ->
            val needle = term.lowercase()
            val inTitle = event.title.lowercase().contains(needle)
            val inDescription = event.description?.lowercase()?.contains(needle) == true
            if (!inTitle && !inDescription) return false
        }

        filter.date?.let { day ->
            val dayStart = day.atStartOfDay()
            val nextDayStart = day.plusDays(1).atStartOfDay()
            if (event.startTime < dayStart || event.startTime >= nextDayStart) return false
        }

        if (filter.upcomingOnly && event.startTime < now) return false

        return true
    }

    override fun create(
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
    ): Event {
        val event = Event(
            id = id,
            venueId = venueId,
            title = title,
            description = description,
            category = category,
            startTime = startTime,
            endTime = endTime,
            basePrice = basePrice,
            status = status,
            posterUrl = posterUrl,
        )
        val venue = venueResolver?.invoke(venueId)
        store[id] = EventWithVenue(
            event = event,
            venueName = venue?.name ?: "Test Venue",
            venueCity = venue?.city ?: "Testville",
            venueAddress = venue?.address ?: "1 Test Street",
        )
        return event
    }

    override fun update(
        id: UUID,
        title: String?,
        description: String?,
        category: String?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        basePrice: BigDecimal?,
        status: EventStatus?,
        posterUrl: String?,
    ): Event? {
        val existing = store[id] ?: return null
        val updated = existing.event.copy(
            title = title ?: existing.event.title,
            description = description ?: existing.event.description,
            category = category ?: existing.event.category,
            startTime = startTime ?: existing.event.startTime,
            endTime = endTime ?: existing.event.endTime,
            basePrice = basePrice ?: existing.event.basePrice,
            status = status ?: existing.event.status,
            posterUrl = posterUrl ?: existing.event.posterUrl,
        )
        store[id] = existing.copy(event = updated)
        return updated
    }

    override fun updateStatus(id: UUID, status: EventStatus): Boolean {
        val existing = store[id] ?: return false
        store[id] = existing.copy(event = existing.event.copy(status = status))
        return true
    }

    override fun distinctCategories(): List<String> =
        store.values.mapNotNull { it.event.category }.distinct().sorted()

    override fun distinctCities(): List<String> =
        store.values.map { it.venueCity }.distinct().sorted()

    override fun countAll(): Long = store.size.toLong()

    // ---- test helpers ----

    fun put(row: EventWithVenue) {
        store[row.event.id] = row
    }
}
