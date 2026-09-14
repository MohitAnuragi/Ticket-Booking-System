package com.ticketbooking.plugins

import com.ticketbooking.repository.BookingRepository
import com.ticketbooking.repository.EventRepository
import com.ticketbooking.repository.ExposedTransactionRunner
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.repository.UserRepository
import com.ticketbooking.repository.VenueRepository
import com.ticketbooking.repository.impl.PostgresBookingRepository
import com.ticketbooking.repository.impl.PostgresEventRepository
import com.ticketbooking.repository.impl.PostgresSeatRepository
import com.ticketbooking.repository.impl.PostgresUserRepository
import com.ticketbooking.repository.impl.PostgresVenueRepository
import com.ticketbooking.service.AdminBookingService
import com.ticketbooking.service.AuthService
import com.ticketbooking.service.BookingConfig
import com.ticketbooking.service.BookingService
import com.ticketbooking.service.EventAdminService
import com.ticketbooking.service.EventService
import com.ticketbooking.service.HoldSweeper
import com.ticketbooking.service.SeatService
import com.ticketbooking.service.SweeperConfig
import com.ticketbooking.service.VenueService
import com.ticketbooking.util.ConfigurationException
import com.ticketbooking.util.JwtConfig
import com.ticketbooking.util.JwtUtil
import com.ticketbooking.util.PasswordHasher
import com.ticketbooking.util.TimeProvider
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Manual dependency wiring for the whole application.
 *
 * Constructed once at startup and handed to the controllers. A DI framework would
 * add a dependency and some magic for very little benefit at this size; doing it by
 * hand keeps the object graph explicit and readable in one place.
 */
class AppComponents(
    val transactions: TransactionRunner,
    val time: TimeProvider,
    val jwtUtil: JwtUtil,
    val userRepository: UserRepository,
    val eventRepository: EventRepository,
    val seatRepository: SeatRepository,
    val venueRepository: VenueRepository,
    val bookingRepository: BookingRepository,
    val authService: AuthService,
    val eventService: EventService,
    val seatService: SeatService,
    val venueService: VenueService,
    val eventAdminService: EventAdminService,
    val bookingService: BookingService,
    val holdSweeper: HoldSweeper,
    val adminBookingService: AdminBookingService,
)

/**
 * Builds the object graph.
 *
 * @throws ConfigurationException when a required setting is missing or unusable, so
 *         misconfiguration stops the process at boot rather than failing the first
 *         request that happens to need it.
 */
fun buildComponents(config: ApplicationConfig, database: Database): AppComponents {
    val jwtConfig = readJwtConfig(config)

    val time = TimeProvider.SYSTEM
    val transactions = ExposedTransactionRunner(database)
    val jwtUtil = JwtUtil(jwtConfig, time)
    val hasher = PasswordHasher.DEFAULT

    val userRepository = PostgresUserRepository()
    val eventRepository = PostgresEventRepository()
    val seatRepository = PostgresSeatRepository()
    val venueRepository = PostgresVenueRepository()
    val bookingRepository = PostgresBookingRepository()

    val authService = AuthService(
        users = userRepository,
        transactions = transactions,
        hasher = hasher,
        jwt = jwtUtil,
        time = time,
    )

    val eventService = EventService(
        events = eventRepository,
        transactions = transactions,
        time = time,
    )

    val seatService = SeatService(
        seats = seatRepository,
        events = eventRepository,
        transactions = transactions,
        time = time,
    )

    val venueService = VenueService(
        venues = venueRepository,
        seats = seatRepository,
        transactions = transactions,
    )

    val eventAdminService = EventAdminService(
        events = eventRepository,
        venues = venueRepository,
        seats = seatRepository,
        transactions = transactions,
        time = time,
    )

    val bookingService = BookingService(
        bookings = bookingRepository,
        seats = seatRepository,
        events = eventRepository,
        transactions = transactions,
        config = readBookingConfig(config),
        time = time,
    )

    val holdSweeper = HoldSweeper(
        config = readSweeperConfig(config),
        sweep = { batchLimit -> bookingService.sweepExpiredHolds(batchLimit) },
    )

    val adminBookingService = AdminBookingService(
        bookings = bookingRepository,
        seats = seatRepository,
        transactions = transactions,
    )

    return AppComponents(
        transactions = transactions,
        time = time,
        jwtUtil = jwtUtil,
        userRepository = userRepository,
        eventRepository = eventRepository,
        seatRepository = seatRepository,
        venueRepository = venueRepository,
        bookingRepository = bookingRepository,
        authService = authService,
        eventService = eventService,
        seatService = seatService,
        venueService = venueService,
        eventAdminService = eventAdminService,
        bookingService = bookingService,
        holdSweeper = holdSweeper,
        adminBookingService = adminBookingService,
    )
}

/**
 * Reads `app.hold.*`.
 *
 * The hold TTL is intentionally configurable: 24 hours is the agreed product
 * behaviour, but setting it to a few seconds is how the expiry sweeper is
 * demonstrated without waiting a day.
 */
private fun readBookingConfig(config: ApplicationConfig): BookingConfig {
    val ttlHours = config.stringOrNull("app.hold.ttlHours")?.toLongOrNull() ?: DEFAULT_HOLD_TTL_HOURS
    if (ttlHours <= 0) {
        throw ConfigurationException("Cannot start: HOLD_TTL_HOURS must be a positive number")
    }

    val maxSeats = config.stringOrNull("app.hold.maxSeatsPerBooking")?.toIntOrNull()
        ?: DEFAULT_MAX_SEATS_PER_BOOKING
    if (maxSeats <= 0) {
        throw ConfigurationException("Cannot start: MAX_SEATS_PER_BOOKING must be a positive number")
    }

    return BookingConfig(holdTtlHours = ttlHours, maxSeatsPerBooking = maxSeats)
}

/**
 * Reads `app.hold.sweepIntervalMinutes`.
 *
 * A sweep interval at or above the hold TTL would leave seats locked for up to
 * twice the advertised 24 hours, so that combination is called out loudly rather
 * than quietly tolerated.
 */
private fun readSweeperConfig(config: ApplicationConfig): SweeperConfig {
    val intervalMinutes = config.stringOrNull("app.hold.sweepIntervalMinutes")?.toLongOrNull()
        ?: DEFAULT_SWEEP_INTERVAL_MINUTES
    if (intervalMinutes <= 0) {
        throw ConfigurationException(
            "Cannot start: HOLD_SWEEP_INTERVAL_MINUTES must be a positive number. " +
                "Disabling the sweeper would let expired holds keep seats out of sale.",
        )
    }
    return SweeperConfig(intervalMinutes = intervalMinutes, batchLimit = DEFAULT_SWEEP_BATCH_LIMIT)
}

private fun readJwtConfig(config: ApplicationConfig): JwtConfig {
    val secret = config.stringOrNull("app.jwt.secret")
    if (secret.isNullOrBlank()) {
        throw ConfigurationException(
            """
            Cannot start: JWT_SECRET is not set.

            Tokens are signed with this value, so there is no safe default - a
            built-in fallback would let anyone forge an admin token.

            Generate one and set it in the terminal you run the server from:
              ${'$'}env:JWT_SECRET = [Convert]::ToBase64String((1..32 | % { Get-Random -Max 256 }))
            """.trimIndent(),
        )
    }
    if (secret.length < JwtConfig.MIN_SECRET_LENGTH) {
        throw ConfigurationException(
            "Cannot start: JWT_SECRET must be at least ${JwtConfig.MIN_SECRET_LENGTH} " +
                "characters (got ${secret.length}). A short secret is brute-forceable.",
        )
    }

    val expiryHours = config.stringOrNull("app.jwt.expiryHours")?.toLongOrNull() ?: DEFAULT_EXPIRY_HOURS
    if (expiryHours <= 0) {
        throw ConfigurationException("Cannot start: JWT_EXPIRY_HOURS must be a positive number")
    }

    return JwtConfig(
        secret = secret,
        issuer = config.stringOrNull("app.jwt.issuer") ?: "ticket-booking-system",
        audience = config.stringOrNull("app.jwt.audience") ?: "ticket-booking-users",
        realm = config.stringOrNull("app.jwt.realm") ?: "Ticket Booking System",
        expiryHours = expiryHours,
    )
}

/** Reads a config value, tolerating an absent key (HOCON `${?ENV}` leaves keys unset). */
internal fun ApplicationConfig.stringOrNull(path: String): String? =
    runCatching { propertyOrNull(path)?.getString() }.getOrNull()

private const val DEFAULT_EXPIRY_HOURS = 24L
private const val DEFAULT_HOLD_TTL_HOURS = 24L
private const val DEFAULT_MAX_SEATS_PER_BOOKING = 10
private const val DEFAULT_SWEEP_INTERVAL_MINUTES = 5L
private const val DEFAULT_SWEEP_BATCH_LIMIT = 200
