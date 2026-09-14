package com.ticketbooking.dto

import kotlinx.serialization.Serializable

/** POST /api/auth/register body. */
@Serializable
data class RegisterRequest(
    val name: String? = null,
    val email: String? = null,
    val password: String? = null,
)

/** POST /api/auth/login body. */
@Serializable
data class LoginRequest(
    val email: String? = null,
    val password: String? = null,
)

/**
 * A user as exposed over the API.
 *
 * Never carries the password hash - this type is the reason a hash cannot leak
 * into a response by accident.
 */
@Serializable
data class UserResponse(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
)

/** POST /api/auth/login 200 response. */
@Serializable
data class AuthResponse(
    val token: String,
    /** Lifetime of [token] in seconds, so the client can pre-empt expiry. */
    val expiresIn: Long,
    val user: UserResponse,
)
