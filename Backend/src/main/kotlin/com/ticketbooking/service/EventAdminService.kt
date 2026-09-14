package com.ticketbooking.service

import com.ticketbooking.dto.CreateEventRequest
import com.ticketbooking.dto.CreateEventResponse
import com.ticketbooking.dto.EventDetailResponse
import com.ticketbooking.dto.UpdateEventRequest
import com.ticketbooking.dto.toDetailResponse
import com.ticketbooking.model.EventStatus
import com.ticketbooking.repository.EventRepository
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.repository.VenueRepository
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.ValidationException
import com.ticketbooking.util.Validators
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Admin event management.
 *
 * The important behaviour here is that creating an event AUTOMATICALLY generates one
 * `event_seats` row per venue seat, inside the same transaction. Without that an
 * event would exist with an empty seat map and be silently unbookable; making it
 * automatic means an admin cannot forget the step.
 */
class EventAdminService(
    private val events: EventRepository,
    private val venues: VenueRepository,
    private val seats: SeatRepository,
    private val transactions: TransactionRunner,
    private val time: TimeProvider = TimeProvider.SYSTEM,
) {

    private val log = LoggerFactory.getLogger(EventAdminService::class.java)

    /**
     * Creates an event and its seat rows.
     *
     * @throws NotFoundException if the venue does not exist.
     * @throws ConflictException if the venue has no seats - the event would be
     *         unbookable, so this is refused rather than silently allowed.
     */
    fun createEvent(request: CreateEventRequest): CreateEventResponse {
        val venueId = Validators.parseUuid(request.venueId, "venueId")
        val title = Validators.requireText(request.title, "title", max = MAX_TITLE_LENGTH)
        val description = Validators.optionalText(request.description, "description", max = MAX_DESCRIPTION_LENGTH)
        val category = Validators.optionalText(request.category, "category", max = MAX_CATEGORY_LENGTH)
        val posterUrl = Validators.optionalText(request.posterUrl, "posterUrl", max = MAX_URL_LENGTH)

        val startTime = parseTimestamp(request.startTime, "startTime")
        val endTime = parseTimestamp(request.endTime, "endTime")
        validateTimeRange(startTime, endTime, requireFuture = true)

        val basePrice = parsePrice(request.basePrice)

        val status = request.status
            ?.takeIf { it.isNotBlank() }
            ?.let { EventStatus.parse(it) }
            ?: EventStatus.PUBLISHED
        if (status == EventStatus.CANCELLED) {
            throw ValidationException("a new event cannot be created as CANCELLED", "status")
        }

        return transactions.inTransaction {
            venues.findById(venueId) ?: throw NotFoundException("Venue not found")

            val venueSeats = seats.findSeatsByVenue(venueId)
            if (venueSeats.isEmpty()) {
                throw ConflictException(
                    errorCode = "VENUE_HAS_NO_SEATS",
                    message = "This venue has no seat layout yet, so the event would have " +
                        "nothing to sell. Generate seats with POST /api/admin/venues/{id}/seats first.",
                )
            }

            val eventId = UUID.randomUUID()
            events.create(
                id = eventId,
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

            // Same transaction as the event insert: either both exist or neither does.
            val generated = seats.createEventSeats(eventId, venueSeats.map { it.id })

            log.info("Created event $eventId with $generated seat rows from venue $venueId")

            val detail = events.findByIdWithVenue(eventId)
                ?: error("event $eventId disappeared inside its own transaction")

            CreateEventResponse(
                event = detail.toDetailResponse(),
                seatsGenerated = generated,
            )
        }
    }

    /**
     * Patches an event. Omitted fields keep their current value.
     *
     * When only one end of the time range is supplied, it is validated against the
     * stored value for the other end, so a partial update cannot leave an event
     * ending before it starts.
     *
     * The past-start restriction is NOT applied here: an admin must be able to
     * correct details of an event that has already begun.
     */
    fun updateEvent(rawId: String?, request: UpdateEventRequest): EventDetailResponse {
        val id = Validators.parseUuid(rawId, "id")

        val title = request.title?.let { Validators.requireText(it, "title", max = MAX_TITLE_LENGTH) }
        val description = request.description?.let {
            Validators.optionalText(it, "description", max = MAX_DESCRIPTION_LENGTH)
        }
        val category = request.category?.let { Validators.optionalText(it, "category", max = MAX_CATEGORY_LENGTH) }
        val posterUrl = request.posterUrl?.let { Validators.optionalText(it, "posterUrl", max = MAX_URL_LENGTH) }
        val startTime = request.startTime?.let { parseTimestamp(it, "startTime") }
        val endTime = request.endTime?.let { parseTimestamp(it, "endTime") }
        val basePrice = request.basePrice?.let { parsePrice(it) }
        val status = request.status?.takeIf { it.isNotBlank() }?.let { EventStatus.parse(it) }

        return transactions.inTransaction {
            val existing = events.findById(id) ?: throw NotFoundException("Event not found")

            validateTimeRange(
                start = startTime ?: existing.startTime,
                end = endTime ?: existing.endTime,
                requireFuture = false,
            )

            events.update(
                id = id,
                title = title,
                description = description,
                category = category,
                startTime = startTime,
                endTime = endTime,
                basePrice = basePrice,
                status = status,
                posterUrl = posterUrl,
            ) ?: throw NotFoundException("Event not found")

            log.info("Updated event $id")

            val detail = events.findByIdWithVenue(id)
                ?: error("event $id disappeared inside its own transaction")
            detail.toDetailResponse()
        }
    }

    /**
     * Cancels an event.
     *
     * A soft delete on purpose: `bookings.event_id` references this row, so a hard
     * delete would either fail on the foreign key or destroy booking history.
     * Cancelling keeps every past booking resolvable while removing the event from
     * public listings.
     */
    fun cancelEvent(rawId: String?): EventDetailResponse {
        val id = Validators.parseUuid(rawId, "id")

        return transactions.inTransaction {
            val existing = events.findById(id) ?: throw NotFoundException("Event not found")

            if (existing.status != EventStatus.CANCELLED) {
                events.updateStatus(id, EventStatus.CANCELLED)
                log.info("Cancelled event $id")
            } else {
                // Idempotent: cancelling twice is not an error.
                log.debug("Event $id was already cancelled")
            }

            val detail = events.findByIdWithVenue(id)
                ?: error("event $id disappeared inside its own transaction")
            detail.toDetailResponse()
        }
    }

    /** Admin listing: includes DRAFT and CANCELLED events. */
    fun listAllEvents(
        search: String? = null,
        city: String? = null,
        category: String? = null,
        date: String? = null,
    ) = EventService(events, transactions, time).listEvents(
        search = search,
        city = city,
        category = category,
        date = date,
        includeUnpublished = true,
    )

    /** Admin detail: reachable even for a DRAFT event. */
    fun getEvent(rawId: String?): EventDetailResponse =
        EventService(events, transactions, time).getEvent(rawId, includeUnpublished = true)

    // ---------------- validation helpers ----------------

    private fun parseTimestamp(raw: String?, field: String): LocalDateTime {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("$field is required", field)
        return try {
            LocalDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            throw ValidationException(
                "$field must be an ISO-8601 date-time such as 2026-10-01T19:00:00",
                field,
            )
        }
    }

    private fun validateTimeRange(start: LocalDateTime, end: LocalDateTime, requireFuture: Boolean) {
        if (!end.isAfter(start)) {
            throw ValidationException("endTime must be after startTime", "endTime")
        }
        if (requireFuture && !start.isAfter(time.nowUtc())) {
            // Selling tickets for something that has already started is almost
            // certainly a data-entry mistake.
            throw ValidationException("startTime must be in the future", "startTime")
        }
    }

    private fun parsePrice(raw: String?): BigDecimal {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("basePrice is required", "basePrice")

        val parsed = try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            throw ValidationException("basePrice must be a decimal number", "basePrice")
        }

        if (parsed <= BigDecimal.ZERO) {
            throw ValidationException("basePrice must be greater than zero", "basePrice")
        }
        // base_price is NUMERIC(10,2): 8 digits before the decimal point.
        if (parsed > MAX_BASE_PRICE) {
            throw ValidationException("basePrice must be at most $MAX_BASE_PRICE", "basePrice")
        }
        return parsed.setScale(2, java.math.RoundingMode.HALF_UP)
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 5_000
        const val MAX_CATEGORY_LENGTH = 60
        const val MAX_URL_LENGTH = 1_000
        val MAX_BASE_PRICE: BigDecimal = BigDecimal("99999999.99")
    }
}
