package com.ticketbooking.controller

import com.ticketbooking.dto.HoldRequest
import com.ticketbooking.plugins.JWT_AUTH
import com.ticketbooking.plugins.requireUser
import com.ticketbooking.service.BookingService
import com.ticketbooking.util.JwtUtil
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Seat holding.
 *
 * Mounted separately from [eventRoutes] even though the path sits under /events,
 * because this route requires authentication while event browsing is public.
 *
 *   POST /api/events/{id}/hold - hold seats for 24 hours (creates a PENDING booking)
 */
fun Route.holdRoutes(bookingService: BookingService, jwtUtil: JwtUtil) {
    authenticate(JWT_AUTH) {
        route("/events/{id}/hold") {
            post {
                val caller = requireUser(jwtUtil)
                val request = call.receive<HoldRequest>()
                val hold = bookingService.holdSeats(
                    userId = caller.userId,
                    rawEventId = call.parameters["id"],
                    request = request,
                )
                // 201: a PENDING booking resource now exists at /api/bookings/{id}.
                call.respond(HttpStatusCode.Created, hold)
            }
        }
    }
}

/**
 * Booking lifecycle.
 *
 *   POST   /api/bookings/{id}/confirm - turn a hold into a confirmed booking
 *   GET    /api/bookings/me           - the caller's booking history
 *   GET    /api/bookings/{id}         - one booking's details
 *   DELETE /api/bookings/{id}         - cancel a hold or a confirmed booking
 *
 * All routes require authentication and act only on the caller's own bookings.
 */
fun Route.bookingRoutes(bookingService: BookingService, jwtUtil: JwtUtil) {
    authenticate(JWT_AUTH) {
        route("/bookings") {

            post("/{id}/confirm") {
                val caller = requireUser(jwtUtil)
                val booking = bookingService.confirmBooking(
                    userId = caller.userId,
                    rawBookingId = call.parameters["id"],
                )
                call.respond(HttpStatusCode.OK, booking)
            }

            // Declared before /{id} so the literal segment is unambiguous to a
            // reader, even though Ktor already prefers constants over parameters.
            get("/me") {
                val caller = requireUser(jwtUtil)
                call.respond(HttpStatusCode.OK, bookingService.listMyBookings(caller.userId))
            }

            get("/{id}") {
                val caller = requireUser(jwtUtil)
                val booking = bookingService.getBooking(
                    userId = caller.userId,
                    rawBookingId = call.parameters["id"],
                )
                call.respond(HttpStatusCode.OK, booking)
            }

            // DELETE, not POST /cancel: cancelling is idempotent and the client
            // addresses the booking resource itself. The response body carries the
            // updated booking so the UI can re-render without a second fetch.
            delete("/{id}") {
                val caller = requireUser(jwtUtil)
                val cancelled = bookingService.cancelBooking(
                    userId = caller.userId,
                    rawBookingId = call.parameters["id"],
                )
                call.respond(HttpStatusCode.OK, cancelled)
            }
        }
    }
}
