package com.ticketbooking.repository.impl

import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import com.ticketbooking.model.Events
import com.ticketbooking.model.Venues
import com.ticketbooking.repository.EventFilter
import com.ticketbooking.repository.EventRepository
import com.ticketbooking.repository.EventWithVenue
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * PostgreSQL implementation of [EventRepository].
 *
 * Listing queries join `venues` so a page of events needs one round trip rather
 * than one query per row.
 *
 * Must be called inside a transaction (see [com.ticketbooking.repository.TransactionRunner]).
 */
class PostgresEventRepository : EventRepository {

    override fun findById(id: UUID): Event? =
        Events.selectAll()
            .where { Events.id eq id }
            .limit(1)
            .singleOrNull()
            ?.toEvent()

    override fun findByIdWithVenue(id: UUID): EventWithVenue? =
        (Events innerJoin Venues)
            .selectAll()
            .where { Events.id eq id }
            .limit(1)
            .singleOrNull()
            ?.toEventWithVenue()

    override fun search(filter: EventFilter): List<EventWithVenue> {
        var condition: Op<Boolean> = Op.TRUE

        filter.statuses?.takeIf { it.isNotEmpty() }?.let { statuses ->
            condition = condition and (Events.status inList statuses.map { it.name })
        }

        filter.city?.let { city ->
            // Case-insensitive exact match: "mumbai" and "Mumbai" are the same city.
            condition = condition and (Venues.city.lowerCase() eq city.lowercase())
        }

        filter.category?.let { category ->
            condition = condition and (Events.category.lowerCase() eq category.lowercase())
        }

        filter.search?.let { term ->
            // Substring match across title and description. `like` on lower-cased
            // columns keeps it case-insensitive; the % wildcards are added here and
            // the term itself is escaped so a user typing "%" cannot widen the search.
            val pattern = "%${escapeLikeWildcards(term.lowercase())}%"
            condition = condition and (
                (Events.title.lowerCase() like pattern) or
                    (Events.description.lowerCase() like pattern)
                )
        }

        filter.date?.let { day ->
            // Half-open interval on start_time so the query can still use the index,
            // rather than wrapping the column in a date() call.
            val dayStart = day.atStartOfDay()
            val nextDayStart = day.plusDays(1).atStartOfDay()
            condition = condition and
                (Events.startTime greaterEq dayStart) and
                (Events.startTime less nextDayStart)
        }

        if (filter.upcomingOnly) {
            condition = condition and (Events.startTime greaterEq LocalDateTime.now(java.time.ZoneOffset.UTC))
        }

        return (Events innerJoin Venues)
            .selectAll()
            .where { condition }
            .orderBy(Events.startTime to SortOrder.ASC)
            .map { it.toEventWithVenue() }
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
        Events.insert {
            it[Events.id] = id
            it[Events.venueId] = venueId
            it[Events.title] = title
            it[Events.description] = description
            it[Events.category] = category
            it[Events.startTime] = startTime
            it[Events.endTime] = endTime
            it[Events.basePrice] = basePrice
            it[Events.status] = status.name
            it[Events.posterUrl] = posterUrl
        }
        return Event(
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
        val updated = Events.update({ Events.id eq id }) { row ->
            title?.let { row[Events.title] = it }
            description?.let { row[Events.description] = it }
            category?.let { row[Events.category] = it }
            startTime?.let { row[Events.startTime] = it }
            endTime?.let { row[Events.endTime] = it }
            basePrice?.let { row[Events.basePrice] = it }
            status?.let { row[Events.status] = it.name }
            posterUrl?.let { row[Events.posterUrl] = it }
        }
        return if (updated == 0) null else findById(id)
    }

    override fun updateStatus(id: UUID, status: EventStatus): Boolean =
        Events.update({ Events.id eq id }) { it[Events.status] = status.name } > 0

    override fun distinctCategories(): List<String> =
        Events.selectAll()
            .mapNotNull { it[Events.category] }
            .distinct()
            .sorted()

    override fun distinctCities(): List<String> =
        (Events innerJoin Venues)
            .selectAll()
            .map { it[Venues.city] }
            .distinct()
            .sorted()

    override fun countAll(): Long = Events.selectAll().count()

    private fun ResultRow.toEvent() = Event(
        id = this[Events.id],
        venueId = this[Events.venueId],
        title = this[Events.title],
        description = this[Events.description],
        category = this[Events.category],
        startTime = this[Events.startTime],
        endTime = this[Events.endTime],
        basePrice = this[Events.basePrice],
        status = EventStatus.from(this[Events.status]),
        posterUrl = this[Events.posterUrl],
    )

    private fun ResultRow.toEventWithVenue() = EventWithVenue(
        event = toEvent(),
        venueName = this[Venues.name],
        venueCity = this[Venues.city],
        venueAddress = this[Venues.address],
    )

    /**
     * Escapes LIKE metacharacters in user input.
     *
     * Without this, a search for "100%" would match far more than intended, and a
     * lone "_" would match any single character.
     */
    private fun escapeLikeWildcards(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
