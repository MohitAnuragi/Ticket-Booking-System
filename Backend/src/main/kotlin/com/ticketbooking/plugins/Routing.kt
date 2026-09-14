package com.ticketbooking.plugins

import com.ticketbooking.controller.adminRoutes
import com.ticketbooking.controller.authRoutes
import com.ticketbooking.controller.bookingRoutes
import com.ticketbooking.controller.eventRoutes
import com.ticketbooking.controller.holdRoutes
import com.ticketbooking.dto.HealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Single place where every route in the application is wired up.
 *
 * Controllers own their routes as `Route` extension functions; this file only
 * composes them under the `/api` base path from spec section 6, which keeps the
 * whole URL layout visible in one screen.
 */
fun Application.configureRouting(components: AppComponents) {
    routing {
        route("/api") {
            healthRoutes()
            authRoutes(components.authService, components.jwtUtil)
            eventRoutes(components.eventService, components.seatService)
            adminRoutes(
                components.venueService,
                components.eventAdminService,
                components.adminBookingService,
                components.jwtUtil,
            )
            holdRoutes(components.bookingService, components.jwtUtil)
            bookingRoutes(components.bookingService, components.jwtUtil)
        }
    }
}

/**
 * Liveness + readiness in one.
 *
 * Extracted from [configureRouting] so it can be mounted without the dependency
 * graph, which is what lets the HTTP-shell tests run with no database.
 *
 * `database` reflects a real `SELECT 1`, so a green check means the API can
 * actually reach Supabase. Returns 503 when the database is unreachable, so
 * monitoring cannot read a broken deployment as healthy.
 */
fun Route.healthRoutes() {
    get("/health") {
        val dbState = when {
            !DatabaseFactory.isInitialized -> "not_configured"
            DatabaseFactory.probe() -> "connected"
            else -> "unreachable"
        }
        val healthy = dbState == "connected"
        call.respond(
            if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(
                status = if (healthy) "UP" else "DEGRADED",
                database = dbState,
            ),
        )
    }
}
