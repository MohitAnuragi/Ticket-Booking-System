package com.ticketbooking.dto

import kotlinx.serialization.Serializable

/**
 * Standard error envelope used by every failing endpoint.
 * Matches the contract in implementation_plan.md section 6.5:
 *   { "error": "VALIDATION_ERROR", "message": "email is required", "field": "email" }
 *
 * `field` and `unavailableSeats` are omitted from the JSON when null, so simple
 * errors stay a clean three-key object.
 */
@Serializable
data class ApiError(
    val error: String,
    val message: String,
    val field: String? = null,
    val unavailableSeats: List<String>? = null,
)
