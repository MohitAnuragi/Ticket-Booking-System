package com.ticketbooking.util

import io.ktor.http.HttpStatusCode

/**
 * Base class for every error this application raises deliberately.
 *
 * Controllers and services throw these; the StatusPages plugin is the single
 * place that turns them into HTTP responses, which keeps the error contract
 * (see [com.ticketbooking.dto.ApiError]) consistent across all endpoints.
 */
sealed class ApiException(
    val status: HttpStatusCode,
    val errorCode: String,
    override val message: String,
    val field: String? = null,
) : RuntimeException(message)

/** 400 - request body or query parameters failed validation. */
class ValidationException(
    message: String,
    field: String? = null,
) : ApiException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message, field)

/** 401 - no credentials, or credentials that don't check out. */
class UnauthorizedException(
    message: String = "Authentication required",
) : ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", message)

/** 403 - authenticated, but not allowed to do this (e.g. non-admin on an admin route). */
class ForbiddenException(
    message: String = "You do not have permission to perform this action",
) : ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", message)

/** 404 - the addressed resource does not exist. */
class NotFoundException(
    message: String = "Resource not found",
) : ApiException(HttpStatusCode.NotFound, "NOT_FOUND", message)

/** 409 - the request conflicts with current state (generic case). */
open class ConflictException(
    errorCode: String = "CONFLICT",
    message: String,
) : ApiException(HttpStatusCode.Conflict, errorCode, message)

/**
 * 409 raised when one or more requested seats could not be held or booked.
 *
 * Carries the offending event-seat ids so the frontend can grey them out and
 * tell the user exactly which seats were lost, per section 6.3 of the spec.
 */
class SeatsUnavailableException(
    val unavailableSeatIds: List<String>,
    message: String = "One or more selected seats are no longer available",
) : ConflictException("SEATS_UNAVAILABLE", message)
