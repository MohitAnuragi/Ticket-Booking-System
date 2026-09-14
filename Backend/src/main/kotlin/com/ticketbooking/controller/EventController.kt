package com.ticketbooking.controller

import com.ticketbooking.service.EventService
import com.ticketbooking.service.SeatService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Public event browsing (spec section 6.2). No authentication required.
 *
 *   GET /api/events                 - list, search and filter
 *   GET /api/events/filters         - distinct cities/categories for the UI
 *   GET /api/events/{id}            - event detail
 *
 * Only PUBLISHED events are visible here; drafts are reachable only through the
 * admin routes.
 */
fun Route.eventRoutes(eventService: EventService, seatService: SeatService) {
    route("/events") {

        get {
            val query = call.request.queryParameters
            val events = eventService.listEvents(
                search = query["search"],
                city = query["city"],
                category = query["category"],
                date = query["date"],
                // Opt-in so a client can ask for "what can I still book?" without
                // this being a surprising default that hides past events.
                upcomingOnly = query["upcomingOnly"]?.toBooleanStrictOrNull() ?: false,
            )
            call.respond(HttpStatusCode.OK, events)
        }

        /**
         * Declared before "/{id}" so the literal path wins; otherwise "filters"
         * would be captured as an event id and rejected as a malformed UUID.
         */
        get("/filters") {
            call.respond(HttpStatusCode.OK, eventService.listFilterOptions())
        }

        get("/{id}") {
            val event = eventService.getEvent(call.parameters["id"])
            call.respond(HttpStatusCode.OK, event)
        }

        /**
         * Seat map with per-seat availability and resolved prices.
         *
         * Public on purpose: browsing availability before signing in is expected,
         * and the response exposes nothing about who holds a seat.
         */
        get("/{id}/seats") {
            val seatMap = seatService.getSeatMap(call.parameters["id"])
            call.respond(HttpStatusCode.OK, seatMap)
        }
    }
}
