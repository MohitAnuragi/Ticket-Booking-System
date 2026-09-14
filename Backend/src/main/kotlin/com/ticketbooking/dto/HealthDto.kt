package com.ticketbooking.dto

import kotlinx.serialization.Serializable

/**
 * Response for GET /api/health.
 *
 * `database` reports the result of a live connectivity probe, so a green
 * health check means the API can actually reach Supabase - not merely that
 * the process is up.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
)
