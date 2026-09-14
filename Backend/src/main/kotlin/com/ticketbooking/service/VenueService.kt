package com.ticketbooking.service

import com.ticketbooking.dto.CreateVenueRequest
import com.ticketbooking.dto.SeatLayoutRequest
import com.ticketbooking.dto.SeatLayoutResponse
import com.ticketbooking.dto.VenueResponse
import com.ticketbooking.dto.toResponse
import com.ticketbooking.model.SeatType
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.SeatSpec
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.repository.VenueRepository
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.ValidationException
import com.ticketbooking.util.Validators
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

/**
 * Admin venue management and bulk seat layout generation.
 *
 * Layout generation is the interesting part: an admin describes a grid
 * (`rows` x `seatsPerRow`) and this expands it into individual `seats` rows. Doing
 * the expansion server-side keeps the client simple and means the uniqueness rule
 * is enforced in one place.
 */
class VenueService(
    private val venues: VenueRepository,
    private val seats: SeatRepository,
    private val transactions: TransactionRunner,
) {

    private val log = LoggerFactory.getLogger(VenueService::class.java)

    /** All venues with their real seat counts, for the admin list. */
    fun listVenues(): List<VenueResponse> = transactions.inTransaction {
        venues.findAll().map { venue ->
            venue.toResponse(seatCount = seats.countSeatsByVenue(venue.id))
        }
    }

    fun getVenue(rawId: String?): VenueResponse {
        val id = Validators.parseUuid(rawId, "id")
        return transactions.inTransaction {
            val venue = venues.findById(id) ?: throw NotFoundException("Venue not found")
            venue.toResponse(seatCount = seats.countSeatsByVenue(id))
        }
    }

    /**
     * Creates a venue.
     *
     * [CreateVenueRequest.totalCapacity] is optional; when absent the venue starts
     * at zero and is corrected automatically as seats are generated, so an admin is
     * never forced to count seats by hand.
     */
    fun createVenue(request: CreateVenueRequest): VenueResponse {
        val name = Validators.requireText(request.name, "name", max = MAX_NAME_LENGTH)
        val city = Validators.requireText(request.city, "city", max = MAX_CITY_LENGTH)
        val address = Validators.optionalText(request.address, "address", max = MAX_ADDRESS_LENGTH)

        val capacity = request.totalCapacity?.let {
            Validators.requireInRange(it, "totalCapacity", 0, MAX_CAPACITY)
        } ?: 0

        return transactions.inTransaction {
            val venue = venues.create(
                id = UUID.randomUUID(),
                name = name,
                address = address,
                city = city,
                totalCapacity = capacity,
            )
            log.info("Created venue ${venue.id} ($name, $city)")
            venue.toResponse(seatCount = 0)
        }
    }

    /**
     * Expands a row/column description into individual seats.
     *
     * Rejects the whole request if ANY generated label already exists for the venue,
     * rather than inserting the non-clashing subset. A partial layout is worse than
     * none: the admin would have to work out what actually got created. The clashing
     * labels are named in the error so the request can be corrected.
     *
     * @throws ValidationException on an empty or malformed layout.
     * @throws ConflictException if any seat label already exists.
     * @throws NotFoundException if the venue does not exist.
     */
    fun generateSeatLayout(rawVenueId: String?, request: SeatLayoutRequest): SeatLayoutResponse {
        val venueId = Validators.parseUuid(rawVenueId, "id")

        val rows = normaliseRows(request.rows)
        val seatsPerRow = Validators.requireInRange(
            request.seatsPerRow,
            "seatsPerRow",
            1,
            MAX_SEATS_PER_ROW,
        )
        val seatType = request.seatType
            ?.takeIf { it.isNotBlank() }
            ?.let { SeatType.parse(it) }
            ?: SeatType.REGULAR
        val multiplier = parseMultiplier(request.priceMultiplier, seatType)

        val specs = rows.flatMap { row ->
            (1..seatsPerRow).map { number ->
                SeatSpec(
                    seatRow = row,
                    seatNumber = number,
                    seatType = seatType,
                    priceMultiplier = multiplier,
                )
            }
        }

        if (specs.size > MAX_SEATS_PER_REQUEST) {
            throw ValidationException(
                "layout would create ${specs.size} seats; the maximum per request is $MAX_SEATS_PER_REQUEST",
                "seatsPerRow",
            )
        }

        return transactions.inTransaction {
            venues.findById(venueId) ?: throw NotFoundException("Venue not found")

            // Checked up front so the whole request fails cleanly. The database's
            // UNIQUE(venue_id, seat_row, seat_number) is still the final backstop.
            val existing = seats.existingSeatLabels(venueId)
            val clashes = specs.map { "${it.seatRow}${it.seatNumber}" }.filter { it in existing }
            if (clashes.isNotEmpty()) {
                throw ConflictException(
                    errorCode = "SEATS_ALREADY_EXIST",
                    message = "These seats already exist for this venue: " +
                        clashes.take(MAX_REPORTED_CLASHES).joinToString(", ") +
                        if (clashes.size > MAX_REPORTED_CLASHES) " (and ${clashes.size - MAX_REPORTED_CLASHES} more)" else "",
                )
            }

            val created = seats.createSeats(venueId, specs)
            val totalSeats = seats.countSeatsByVenue(venueId)

            // Keep the declared capacity honest as the layout grows.
            venues.updateCapacity(venueId, totalSeats.toInt())

            log.info("Generated ${created.size} seats for venue $venueId (total now $totalSeats)")

            SeatLayoutResponse(
                venueId = venueId.toString(),
                seatsCreated = created.size,
                totalSeats = totalSeats,
                createdSeatLabels = created.take(MAX_PREVIEW_LABELS).map { it.label },
            )
        }
    }

    /**
     * Validates and canonicalises row labels.
     *
     * Upper-cased and de-duplicated so "a" and "A" cannot both be submitted and then
     * collide in the database.
     */
    private fun normaliseRows(raw: List<String>?): List<String> {
        if (raw == null || raw.isEmpty()) {
            throw ValidationException("rows is required and must contain at least one row", "rows")
        }
        if (raw.size > MAX_ROWS) {
            throw ValidationException("rows must contain at most $MAX_ROWS entries", "rows")
        }

        val normalised = raw.map { row ->
            val value = row.trim().uppercase()
            if (value.isEmpty()) {
                throw ValidationException("row labels must not be blank", "rows")
            }
            // seat_row is VARCHAR(5) in the schema.
            if (value.length > MAX_ROW_LABEL_LENGTH) {
                throw ValidationException(
                    "row label '$value' is longer than $MAX_ROW_LABEL_LENGTH characters",
                    "rows",
                )
            }
            if (!value.all { it.isLetterOrDigit() }) {
                throw ValidationException(
                    "row label '$value' must contain only letters and digits",
                    "rows",
                )
            }
            value
        }

        val duplicates = normalised.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ValidationException(
                "rows contains duplicates: ${duplicates.joinToString(", ")}",
                "rows",
            )
        }
        return normalised
    }

    /**
     * @return the requested multiplier, or a sensible default for the tier.
     * @throws ValidationException if it is not a positive number within NUMERIC(4,2).
     */
    private fun parseMultiplier(raw: String?, seatType: SeatType): BigDecimal {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: return defaultMultiplierFor(seatType)

        val parsed = try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            throw ValidationException("priceMultiplier must be a decimal number", "priceMultiplier")
        }

        if (parsed <= BigDecimal.ZERO) {
            throw ValidationException("priceMultiplier must be greater than zero", "priceMultiplier")
        }
        // price_multiplier is NUMERIC(4,2): at most 99.99.
        if (parsed > MAX_MULTIPLIER) {
            throw ValidationException("priceMultiplier must be at most $MAX_MULTIPLIER", "priceMultiplier")
        }
        return parsed.setScale(2, java.math.RoundingMode.HALF_UP)
    }

    private fun defaultMultiplierFor(seatType: SeatType): BigDecimal = when (seatType) {
        SeatType.REGULAR -> BigDecimal("1.00")
        SeatType.PREMIUM -> BigDecimal("1.50")
        SeatType.VIP -> BigDecimal("2.50")
    }

    private companion object {
        const val MAX_NAME_LENGTH = 160
        const val MAX_CITY_LENGTH = 100
        const val MAX_ADDRESS_LENGTH = 500
        const val MAX_CAPACITY = 1_000_000
        const val MAX_ROWS = 100
        const val MAX_ROW_LABEL_LENGTH = 5
        const val MAX_SEATS_PER_ROW = 200
        const val MAX_SEATS_PER_REQUEST = 5_000
        const val MAX_PREVIEW_LABELS = 20
        const val MAX_REPORTED_CLASHES = 10
        val MAX_MULTIPLIER: BigDecimal = BigDecimal("99.99")
    }
}
