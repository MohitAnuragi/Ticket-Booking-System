package com.ticketbooking.util

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import com.ticketbooking.model.Role
import java.util.Date
import java.util.UUID

/** JWT settings, read from `app.jwt.*` at startup. */
data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val realm: String,
    val expiryHours: Long,
) {
    init {
        require(secret.length >= MIN_SECRET_LENGTH) {
            "JWT_SECRET must be at least $MIN_SECRET_LENGTH characters"
        }
        require(expiryHours > 0) { "app.jwt.expiryHours must be positive" }
    }

    companion object {
        const val MIN_SECRET_LENGTH = 32
    }
}

/** The authenticated caller, extracted from a verified token. */
data class AuthenticatedUser(
    val userId: UUID,
    val email: String,
    val role: Role,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
}

/**
 * Issues and verifies JWTs.
 *
 * Stateless auth is the right fit here: the API keeps no session store, so any
 * instance can validate any token using only the shared secret. The trade-off is
 * that a token cannot be revoked before it expires, which is why the lifetime is
 * short (24h by default).
 *
 * The token carries `userId` and `role` so authorisation decisions need no
 * database round-trip. Role is re-read from the token, not the database, so a
 * role change only takes effect on the user's next login - an acceptable trade
 * for this scope, and worth stating explicitly.
 *
 * NOTE ON TIME: [time] controls the `iat`/`exp` stamped on issued tokens, which is
 * what lets expiry be tested without sleeping. Verification, however, always uses
 * the real system clock - java-jwt 4.6 removed the ability to inject a clock into
 * [JWTVerifier]. Tests must therefore anchor their clock near real "now" and vary
 * offsets from it, rather than pinning an arbitrary date.
 */
class JwtUtil(
    private val config: JwtConfig,
    private val time: TimeProvider = TimeProvider.SYSTEM,
) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)

    val realm: String get() = config.realm

    /** Verifier for Ktor's `jwt` authentication provider. */
    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    /** Seconds until a freshly issued token expires; returned to clients. */
    val expiresInSeconds: Long get() = config.expiryHours * SECONDS_PER_HOUR

    fun generateToken(userId: UUID, email: String, role: Role): String {
        val issuedAt = time.nowOffset().toInstant()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId.toString())
            .withClaim(CLAIM_USER_ID, userId.toString())
            .withClaim(CLAIM_EMAIL, email)
            .withClaim(CLAIM_ROLE, role.name)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plusSeconds(expiresInSeconds)))
            .sign(algorithm)
    }

    /**
     * Reads the caller out of a verified token payload.
     *
     * @return null when required claims are missing or malformed, so a structurally
     *         valid but nonsensical token is rejected rather than trusted.
     */
    fun extractUser(payload: Payload): AuthenticatedUser? {
        val userId = payload.getClaim(CLAIM_USER_ID).asString()?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        } ?: return null
        val email = payload.getClaim(CLAIM_EMAIL).asString() ?: return null
        val role = payload.getClaim(CLAIM_ROLE).asString() ?: return null

        // Unknown role strings must not fall back to something permissive.
        val parsedRole = Role.entries.firstOrNull { it.name == role } ?: return null

        return AuthenticatedUser(userId, email, parsedRole)
    }

    companion object {
        const val CLAIM_USER_ID = "userId"
        const val CLAIM_EMAIL = "email"
        const val CLAIM_ROLE = "role"
        private const val SECONDS_PER_HOUR = 3600L
    }
}
