package com.ticketbooking.controller

import com.ticketbooking.dto.CreateEventRequest
import com.ticketbooking.dto.CreateVenueRequest
import com.ticketbooking.dto.SeatLayoutRequest
import com.ticketbooking.dto.UpdateEventRequest
import com.ticketbooking.plugins.JWT_AUTH
import com.ticketbooking.plugins.requireAdmin
import com.ticketbooking.service.AdminBookingService
import com.ticketbooking.service.EventAdminService
import com.ticketbooking.service.VenueService
import com.ticketbooking.util.JwtUtil
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Admin endpoints (spec section 6.4). Every route requires a valid JWT whose role
 * is ADMIN.
 *
 * Two layers of protection, deliberately:
 *   1. `authenticate(JWT_AUTH)` rejects anyone without a valid token (401).
 *   2. [requireAdmin] rejects a valid token belonging to a normal user (403).
 *
 * The second is not redundant - the JWT provider only proves the token is genuine,
 * it says nothing about authorisation.
 */
fun Route.adminRoutes(
    venueService: VenueService,
    eventAdminService: EventAdminService,
    adminBookingService: AdminBookingService,
    jwtUtil: JwtUtil,
) {
    authenticate(JWT_AUTH) {
        route("/admin") {

            // ---------------- Venues ----------------

            get("/venues") {
                requireAdmin(jwtUtil)
                call.respond(HttpStatusCode.OK, venueService.listVenues())
            }

            post("/venues") {
                requireAdmin(jwtUtil)
                val request = call.receive<CreateVenueRequest>()
                call.respond(HttpStatusCode.Created, venueService.createVenue(request))
            }

            get("/venues/{id}") {
                requireAdmin(jwtUtil)
                call.respond(HttpStatusCode.OK, venueService.getVenue(call.parameters["id"]))
            }

            /**
             * Bulk seat layout generation: expands rows x seatsPerRow into seats.
             * Can be called repeatedly to add tiers, e.g. rows A-C as PREMIUM then
             * rows D-K as REGULAR.
             */
            post("/venues/{id}/seats") {
                requireAdmin(jwtUtil)
                val request = call.receive<SeatLayoutRequest>()
                call.respond(
                    HttpStatusCode.Created,
                    venueService.generateSeatLayout(call.parameters["id"], request),
                )
            }

            // ---------------- Events ----------------

            /** Admin listing: unlike the public route, includes DRAFT and CANCELLED. */
            get("/events") {
                requireAdmin(jwtUtil)
                val query = call.request.queryParameters
                call.respond(
                    HttpStatusCode.OK,
                    eventAdminService.listAllEvents(
                        search = query["search"],
                        city = query["city"],
                        category = query["category"],
                        date = query["date"],
                    ),
                )
            }

            get("/events/{id}") {
                requireAdmin(jwtUtil)
                call.respond(HttpStatusCode.OK, eventAdminService.getEvent(call.parameters["id"]))
            }

            /** Creates the event AND one event_seats row per venue seat. */
            post("/events") {
                requireAdmin(jwtUtil)
                val request = call.receive<CreateEventRequest>()
                call.respond(HttpStatusCode.Created, eventAdminService.createEvent(request))
            }

            put("/events/{id}") {
                requireAdmin(jwtUtil)
                val request = call.receive<UpdateEventRequest>()
                call.respond(
                    HttpStatusCode.OK,
                    eventAdminService.updateEvent(call.parameters["id"], request),
                )
            }

            /**
             * Cancels the event rather than deleting the row, because bookings
             * reference it. Responds 200 with the cancelled event.
             */
            delete("/events/{id}") {
                requireAdmin(jwtUtil)
                call.respond(HttpStatusCode.OK, eventAdminService.cancelEvent(call.parameters["id"]))
            }

            // ---------------- Bookings ----------------

            /**
             * Every booking across all users, newest first. Filters combine with AND
             * and are all optional: ?eventId=&userId=&status=&limit=&offset=
             *
             * Paginated because this listing is unbounded by nature - one popular
             * event can produce thousands of bookings.
             */
            get("/bookings") {
                requireAdmin(jwtUtil)
                val query = call.request.queryParameters
                call.respond(
                    HttpStatusCode.OK,
                    adminBookingService.listBookings(
                        rawEventId = query["eventId"],
                        rawUserId = query["userId"],
                        rawStatus = query["status"],
                        rawLimit = query["limit"],
                        rawOffset = query["offset"],
                    ),
                )
            }

            get("/bookings/{id}") {
                requireAdmin(jwtUtil)
                call.respond(HttpStatusCode.OK, adminBookingService.getBooking(call.parameters["id"]))
            }

            /**
             * Cancels any user's booking - the support case, and the one that makes
             * a rescheduled event manageable. The acting admin is recorded in the log.
             */
            delete("/bookings/{id}") {
                requireAdmin(jwtUtil).let { admin ->
                    call.respond(
                        HttpStatusCode.OK,
                        adminBookingService.cancelBooking(admin.userId, call.parameters["id"]),
                    )
                }
            }
        }
    }
}
