package com.ticketbooking.plugins

import com.ticketbooking.dto.ApiError
import com.ticketbooking.util.ApiException
import com.ticketbooking.util.SeatsUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond

/**
 * Single funnel for every error response so the shape in spec section 6.5 is
 * guaranteed, no matter where the failure came from.
 *
 * Deliberate failures ([ApiException]) map to their declared status. Anything
 * unexpected is logged with its stack trace and reported as a generic 500 —
 * internal details are never leaked to the client.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // Errors this application raises on purpose.
        exception<ApiException> { call, cause ->
            val payload = when (cause) {
                is SeatsUnavailableException -> ApiError(
                    error = cause.errorCode,
                    message = cause.message,
                    unavailableSeats = cause.unavailableSeatIds,
                )
                else -> ApiError(
                    error = cause.errorCode,
                    message = cause.message,
                    field = cause.field,
                )
            }
            call.respond(cause.status, payload)
        }

        // Malformed JSON / wrong types reach us as Ktor's BadRequestException.
        exception<BadRequestException> { call, cause ->
            call.application.log.debug("Malformed request at ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    error = "VALIDATION_ERROR",
                    message = "Request body is missing or malformed",
                ),
            )
        }

        // Anything we did not anticipate.
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error at ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    error = "INTERNAL_ERROR",
                    message = "An unexpected error occurred",
                ),
            )
        }

        // Unmatched routes must also honour the standard error shape.
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ApiError(
                    error = "NOT_FOUND",
                    message = "No endpoint matches ${call.request.path()}",
                ),
            )
        }
    }
}
