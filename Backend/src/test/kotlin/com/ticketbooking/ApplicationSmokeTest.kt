package com.ticketbooking

import com.ticketbooking.plugins.configureSerialization
import com.ticketbooking.plugins.configureStatusPages
import com.ticketbooking.plugins.healthRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the HTTP shell: routing, JSON serialization and the global error
 * contract from spec section 6.5.
 *
 * These install the web plugins directly rather than calling [module], because
 * [module] deliberately fails fast without database credentials. Keeping the
 * database out of these tests is what lets them run with no external services -
 * the agreed "unit tests only" approach.
 */
class ApplicationSmokeTest {

    /** The application minus the database: everything these tests exercise. */
    private fun Application.webOnlyModule() {
        configureSerialization()
        configureStatusPages()
        routing { route("/api") { healthRoutes() } }
    }

    @Test
    fun `health endpoint responds and reports database state`() = testApplication {
        application { webOnlyModule() }

        val response = client.get("/api/health")

        // No database configured in tests, so health is honest about it and
        // reports 503 rather than a misleading 200.
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\""), "expected a status field, got: $body")
        assertTrue(body.contains("\"database\""), "expected a database field, got: $body")
        assertTrue(body.contains("not_configured"), "expected not_configured, got: $body")
    }

    @Test
    fun `unknown route returns the standard error envelope`() = testApplication {
        application { webOnlyModule() }

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"error\""), "expected an error field, got: $body")
        assertTrue(body.contains("NOT_FOUND"), "expected NOT_FOUND code, got: $body")
        assertTrue(body.contains("\"message\""), "expected a message field, got: $body")
    }

    @Test
    fun `error envelope omits null optional fields`() = testApplication {
        application { webOnlyModule() }

        val body = client.get("/api/nope").bodyAsText()

        // explicitNulls = false keeps simple errors to error + message only.
        assertTrue(!body.contains("\"field\""), "field should be omitted when null, got: $body")
        assertTrue(
            !body.contains("unavailableSeats"),
            "unavailableSeats should be omitted when null, got: $body",
        )
    }
}
