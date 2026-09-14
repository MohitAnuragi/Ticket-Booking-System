package com.ticketbooking

import com.ticketbooking.plugins.AppComponents
import com.ticketbooking.plugins.configureRouting
import com.ticketbooking.plugins.configureSecurity
import com.ticketbooking.plugins.configureSerialization
import com.ticketbooking.plugins.configureStatusPages
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.EventWithVenue
import com.ticketbooking.repository.FakeBookingRepository
import com.ticketbooking.repository.FakeEventRepository
import com.ticketbooking.repository.FakeSeatRepository
import com.ticketbooking.repository.FakeUserRepository
import com.ticketbooking.repository.FakeVenueRepository
import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import com.ticketbooking.model.SeatType
import com.ticketbooking.model.Venue
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
import com.ticketbooking.util.JwtConfig
import com.ticketbooking.util.JwtUtil
import com.ticketbooking.util.PasswordHasher
import com.ticketbooking.util.TimeProvider
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The endpoints over real HTTP, with in-memory repositories.
 *
 * The service tests prove the RULES; these prove the WIRING, which no service test can:
 * that each route is mounted at the path in the spec, that the auth and role guards are
 * actually attached, that thrown exceptions become the right status code, and that the
 * JSON on the wire carries the field names the frontend reads.
 *
 * The database is deliberately absent. [AppComponents] is constructed by hand from
 * fakes rather than through `buildComponents`, which needs a live [org.jetbrains.exposed.v1.jdbc.Database].
 */
class ApiEndpointTest {

    private val eventId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()

    private val users = FakeUserRepository()
    private val events = FakeEventRepository()
    private val seats = FakeSeatRepository()
    private val venues = FakeVenueRepository()
    private val bookings = FakeBookingRepository()

    private val time = TimeProvider.SYSTEM
    private val transactions = DirectTransactionRunner()

    private val jwtUtil = JwtUtil(
        JwtConfig(
            secret = "test-secret-that-is-long-enough-to-pass-validation",
            issuer = "ticket-booking-system",
            audience = "ticket-booking-users",
            realm = "Ticket Booking System",
            expiryHours = 24,
        ),
        time,
    )

    /** Two seats on a published event, far enough ahead to be bookable. */
    private fun seedCatalogue() {
        venues.put(
            Venue(
                id = venueId,
                name = "NSCI Dome",
                address = "1 Road",
                city = "Mumbai",
                totalCapacity = 2,
            ),
        )
        events.venueResolver = { venues.findById(it) }
        events.put(
            EventWithVenue(
                event = Event(
                    id = eventId,
                    venueId = venueId,
                    title = "Coldplay Live",
                    description = "A concert",
                    category = "Concert",
                    startTime = LocalDateTime.now().plusDays(30),
                    endTime = LocalDateTime.now().plusDays(30).plusHours(3),
                    basePrice = BigDecimal("1000.00"),
                    status = EventStatus.PUBLISHED,
                    posterUrl = null,
                ),
                venueName = "NSCI Dome",
                venueCity = "Mumbai",
                venueAddress = "1 Road",
            ),
        )
        bookings.eventResolver = { events.findByIdWithVenue(it) }
        bookings.seatResolver = { id ->
            seats.findEventSeatMap(eventId).firstOrNull { it.eventSeat.id == id }
                ?.let { Triple(it.seat.seatRow, it.seat.seatNumber, it.seat.seatType) }
        }
    }

    private fun addSeat(row: String, number: Int) =
        seats.addAvailableSeat(eventId, venueId, row, number, SeatType.PREMIUM, BigDecimal("1.50")).id

    private fun buildComponents(): AppComponents {
        val authService = AuthService(users, transactions, PasswordHasher.DEFAULT, jwtUtil, time)
        val eventService = EventService(events, transactions, time)
        val seatService = SeatService(seats, events, transactions, time)
        val venueService = VenueService(venues, seats, transactions)
        val eventAdminService = EventAdminService(events, venues, seats, transactions, time)
        val bookingService = BookingService(
            bookings, seats, events, transactions,
            BookingConfig(holdTtlHours = 24, maxSeatsPerBooking = 10), time,
        )

        return AppComponents(
            transactions = transactions,
            time = time,
            jwtUtil = jwtUtil,
            userRepository = users,
            eventRepository = events,
            seatRepository = seats,
            venueRepository = venues,
            bookingRepository = bookings,
            authService = authService,
            eventService = eventService,
            seatService = seatService,
            venueService = venueService,
            eventAdminService = eventAdminService,
            bookingService = bookingService,
            holdSweeper = HoldSweeper(SweeperConfig()) { bookingService.sweepExpiredHolds(it) },
            adminBookingService = AdminBookingService(bookings, seats, transactions),
        )
    }

    /** The application without the database: plugins plus every route. */
    private fun Application.testModule() {
        val components = buildComponents()
        configureSerialization()
        configureStatusPages()
        configureSecurity(components.jwtUtil)
        configureRouting(components)
    }

    private fun ApplicationTestBuilder.setup() = application { testModule() }

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        json: String,
        token: String? = null,
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        setBody(json)
    }

    /**
     * Collapses `": "` to `":"` so assertions do not depend on pretty-printing.
     *
     * The API pretty-prints its JSON, which is good for humans reading curl output but
     * means a literal `"status":"PENDING"` never matches the wire format. Only the
     * spacing around a colon that follows a key is touched, so spaces inside values
     * ("NSCI Dome") survive.
     */
    private fun String.compactJson(): String = Regex("\"\\s*:\\s*").replace(this, "\":")

    /** Registers and signs in, returning the bearer token. */
    private suspend fun ApplicationTestBuilder.signUp(email: String): String {
        postJson("/api/auth/register", """{"name":"Test User","email":"$email","password":"secret123"}""")
        val body = postJson("/api/auth/login", """{"email":"$email","password":"secret123"}""").bodyAsText()
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.adminToken(): String {
        val email = "admin@example.com"
        users.let {
            // Bootstrapping through the service is what makes an ADMIN; registration
            // only ever creates USERs.
            AuthService(users, transactions, PasswordHasher.DEFAULT, jwtUtil, time)
                .ensureBootstrapAdmin(email, "secret123", "Platform Admin")
        }
        val body = postJson("/api/auth/login", """{"email":"$email","password":"secret123"}""").bodyAsText()
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    // ---------------------------------------------------------------- auth

    @Test
    fun `register returns 201 and never echoes the password`() = testApplication {
        setup()

        val response = postJson(
            "/api/auth/register",
            """{"name":"Mohit","email":"mohit@example.com","password":"secret123"}""",
        )

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText().compactJson()
        assertTrue(body.contains("\"role\":\"USER\""), body)
        // UserResponse has no password field at all; this is the wire-level proof.
        assertFalse(body.contains("password"), "a password must never appear in a response: $body")
        assertFalse(body.contains("hash"), body)
    }

    @Test
    fun `registering the same email twice is a 409 with the documented code`() = testApplication {
        setup()
        val json = """{"name":"Mohit","email":"dupe@example.com","password":"secret123"}"""
        postJson("/api/auth/register", json)

        val response = postJson("/api/auth/register", json)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("EMAIL_ALREADY_REGISTERED"), response.bodyAsText())
    }

    @Test
    fun `login returns the token envelope the frontend expects`() = testApplication {
        setup()
        // Name must be at least 2 characters; a 1-character name makes registration
        // 400 and the login below fail for the wrong reason.
        postJson("/api/auth/register", """{"name":"Alice","email":"a@example.com","password":"secret123"}""")

        val body = postJson("/api/auth/login", """{"email":"a@example.com","password":"secret123"}""")
            .bodyAsText()

        // api.js reads exactly these three.
        assertTrue(body.contains("\"token\""), body)
        assertTrue(body.contains("\"expiresIn\""), body)
        assertTrue(body.contains("\"user\""), body)
    }

    @Test
    fun `wrong password is a 401 in the standard envelope`() = testApplication {
        setup()
        val registered = postJson(
            "/api/auth/register",
            """{"name":"Bob","email":"b@example.com","password":"secret123"}""",
        )
        // Asserted so this test cannot pass vacuously: without a real account, a 401
        // below would prove nothing.
        assertEquals(HttpStatusCode.Created, registered.status)

        val response = postJson("/api/auth/login", """{"email":"b@example.com","password":"wrong"}""")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("UNAUTHORIZED"), response.bodyAsText())
    }

    @Test
    fun `auth me requires a token and then identifies the caller`() = testApplication {
        setup()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me").status)

        val token = signUp("me@example.com")
        val response = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("me@example.com"), response.bodyAsText())
    }

    @Test
    fun `a malformed body is a 400 rather than a 500`() = testApplication {
        setup()

        val response = postJson("/api/auth/login", """{"email": }""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"), response.bodyAsText())
    }

    // -------------------------------------------------------- public events

    @Test
    fun `event listing sends the FLAT venue shape the listing page reads`() = testApplication {
        seedCatalogue()
        setup()

        val body = client.get("/api/events").bodyAsText().compactJson()

        // EventSummaryResponse: venueName and city are top-level. events.js and the
        // admin event table depend on this exact shape.
        assertTrue(body.contains("\"venueName\":\"NSCI Dome\""), body)
        assertTrue(body.contains("\"city\":\"Mumbai\""), body)
        assertFalse(body.contains("\"venue\":{"), "the summary shape must not nest venue: $body")
    }

    @Test
    fun `event detail sends the NESTED venue shape the seat map reads`() = testApplication {
        seedCatalogue()
        setup()

        val body = client.get("/api/events/$eventId").bodyAsText().compactJson()

        // EventDetailResponse nests it; seatmap.js reads event.venue.name/city/address.
        assertTrue(body.contains("\"venue\":{"), body)
        assertTrue(body.contains("\"name\":\"NSCI Dome\""), body)
        assertFalse(body.contains("\"venueName\""), "the detail shape must not flatten venue: $body")
    }

    @Test
    fun `filters endpoint wins over the id route`() = testApplication {
        seedCatalogue()
        setup()

        val response = client.get("/api/events/filters")

        // Declared before /{id}; otherwise "filters" is parsed as a UUID and 400s.
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("cities"), body)
        assertTrue(body.contains("categories"), body)
    }

    @Test
    fun `seat map carries per-seat prices and a summary`() = testApplication {
        seedCatalogue()
        addSeat("A", 1)
        setup()

        val body = client.get("/api/events/$eventId/seats").bodyAsText().compactJson()

        assertTrue(body.contains("\"eventSeatId\""), body)
        // 1000.00 base x 1.50 PREMIUM multiplier, resolved server-side.
        assertTrue(body.contains("\"price\":\"1500.00\""), body)
        assertTrue(body.contains("\"summary\""), body)
    }

    @Test
    fun `an unparseable id is a 400 and an unknown id is a 404`() = testApplication {
        seedCatalogue()
        setup()

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/events/not-a-uuid").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/events/${UUID.randomUUID()}").status)
    }

    // ---------------------------------------------------------- admin guard

    @Test
    fun `admin routes reject anonymous with 401 and a normal user with 403`() = testApplication {
        setup()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/venues").status)

        val userToken = signUp("normal@example.com")
        val forbidden = client.get("/api/admin/venues") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }

        // 403, not 404: the distinction between "log in" and "not allowed" is the
        // whole reason requireAdmin exists on top of authenticate().
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(forbidden.bodyAsText().contains("FORBIDDEN"), forbidden.bodyAsText())
    }

    @Test
    fun `an admin token reaches the admin routes`() = testApplication {
        setup()
        val token = adminToken()

        val response = client.get("/api/admin/venues") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `admin booking listing is paginated with a total`() = testApplication {
        seedCatalogue()
        setup()
        val token = adminToken()

        val body = client.get("/api/admin/bookings?limit=10") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText()

        // manage-bookings.js reads all four.
        assertTrue(body.contains("\"bookings\""), body)
        assertTrue(body.contains("\"total\""), body)
        assertTrue(body.contains("\"limit\""), body)
        assertTrue(body.contains("\"offset\""), body)
    }

    @Test
    fun `an out-of-range limit is refused rather than clamped`() = testApplication {
        setup()
        val token = adminToken()

        val response = client.get("/api/admin/bookings?limit=5000") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -------------------------------------------------------- booking flow

    @Test
    fun `the whole booking flow works over HTTP`() = testApplication {
        seedCatalogue()
        val seatId = addSeat("A", 1)
        setup()
        val token = signUp("buyer@example.com")

        val hold = postJson("/api/events/$eventId/hold", """{"eventSeatIds":["$seatId"]}""", token)
        assertEquals(HttpStatusCode.Created, hold.status)
        val holdBody = hold.bodyAsText().compactJson()
        assertTrue(holdBody.contains("\"status\":\"PENDING\""), holdBody)
        assertTrue(holdBody.contains("\"expiresAt\""), "a live hold must expose its deadline: $holdBody")
        assertTrue(holdBody.contains("\"seatLabels\""), holdBody)
        assertTrue(holdBody.contains("TB-"), holdBody)

        val bookingId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(holdBody)!!.groupValues[1]

        val confirm = postJson("/api/bookings/$bookingId/confirm", "", token)
        assertEquals(HttpStatusCode.OK, confirm.status)
        val confirmBody = confirm.bodyAsText().compactJson()
        assertTrue(confirmBody.contains("\"status\":\"CONFIRMED\""), confirmBody)

        val history = client.get("/api/bookings/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, history.status)
        assertTrue(history.bodyAsText().contains(bookingId), history.bodyAsText())

        val cancel = client.delete("/api/bookings/$bookingId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, cancel.status)
        val cancelBody = cancel.bodyAsText().compactJson()
        assertTrue(cancelBody.contains("\"status\":\"CANCELLED\""), cancelBody)
    }

    @Test
    fun `holding without a token is a 401`() = testApplication {
        seedCatalogue()
        val seatId = addSeat("A", 1)
        setup()

        val response = postJson("/api/events/$eventId/hold", """{"eventSeatIds":["$seatId"]}""")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a taken seat answers 409 with the offending ids`() = testApplication {
        seedCatalogue()
        val seatId = addSeat("A", 1)
        setup()

        val first = signUp("first@example.com")
        postJson("/api/events/$eventId/hold", """{"eventSeatIds":["$seatId"]}""", first)

        val second = signUp("second@example.com")
        val response = postJson("/api/events/$eventId/hold", """{"eventSeatIds":["$seatId"]}""", second)

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SEATS_UNAVAILABLE"), body)
        // seatmap.js greys out exactly these ids.
        assertTrue(body.contains("unavailableSeats"), body)
        assertTrue(body.contains(seatId.toString()), body)
    }

    @Test
    fun `one user cannot read another user's booking`() = testApplication {
        seedCatalogue()
        val seatId = addSeat("A", 1)
        setup()

        val owner = signUp("owner@example.com")
        val holdBody = postJson(
            "/api/events/$eventId/hold",
            """{"eventSeatIds":["$seatId"]}""",
            owner,
        ).bodyAsText()
        val bookingId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(holdBody)!!.groupValues[1]

        val stranger = signUp("stranger@example.com")
        val response = client.get("/api/bookings/$bookingId") {
            header(HttpHeaders.Authorization, "Bearer $stranger")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
