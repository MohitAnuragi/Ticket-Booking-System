package com.ticketbooking.model

import com.ticketbooking.util.ValidationException
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.datetime
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Event lifecycle.
 *
 * Only [PUBLISHED] events are visible to the public browse endpoints; [DRAFT] is
 * admin-only, and [CANCELLED] events stay readable so existing bookings still
 * resolve to something meaningful.
 */
enum class EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    ;

    companion object {
        fun parse(raw: String, field: String = "status"): EventStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "status must be one of ${entries.joinToString(", ") { it.name }}",
                    field,
                )

        fun from(raw: String): EventStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DRAFT
    }
}

/**
 * `events` table - a show at a venue on a date.
 *
 * `start_time` / `end_time` are timezone-naive TIMESTAMPs in the schema and are
 * consistently treated as UTC by the application.
 */
object Events : Table("events") {
    val id = javaUUID("id")
    val venueId = javaUUID("venue_id").references(Venues.id)
    val title = varchar("title", 200)
    val description = text("description").nullable()
    val category = varchar("category", 60).nullable()
    val startTime = datetime("start_time")
    val endTime = datetime("end_time")
    val basePrice = decimal("base_price", 10, 2)
    val status = varchar("status", 20)
    val posterUrl = text("poster_url").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** An event. Per-seat prices are derived as [basePrice] x the seat's multiplier. */
data class Event(
    val id: UUID,
    val venueId: UUID,
    val title: String,
    val description: String?,
    val category: String?,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val basePrice: BigDecimal,
    val status: EventStatus,
    val posterUrl: String?,
) {
    /** Publicly listable events. */
    val isPublic: Boolean get() = status == EventStatus.PUBLISHED

    /** True once the event has started, which closes the cancellation window. */
    fun hasStartedAt(now: LocalDateTime): Boolean = !now.isBefore(startTime)
}
