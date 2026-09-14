package com.ticketbooking.util

import com.ticketbooking.model.Role
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Security primitives: password hashing and token issue/verify. */
class SecurityPrimitivesTest {

    // Cost 4 keeps the suite fast. Production uses PasswordHasher.DEFAULT_COST (12).
    private val hasher = PasswordHasher(cost = 4)

    // Anchored to real "now" because JWTVerifier validates against the system clock
    // (java-jwt 4.6 removed clock injection). Offsets from this anchor are what the
    // expiry assertions actually depend on, so the tests stay deterministic.
    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
    private val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-long-enough-for-hmac256",
        issuer = "ticket-booking-test",
        audience = "ticket-booking-test-users",
        realm = "test",
        expiryHours = 24,
    )

    private fun jwtAt(moment: OffsetDateTime) =
        JwtUtil(jwtConfig, TimeProvider.fixedAt(moment))

    // ---------------- PasswordHasher ----------------

    @Test
    fun `hash then verify succeeds`() {
        val hash = hasher.hash("secret123")
        assertTrue(hasher.verify("secret123", hash))
    }

    @Test
    fun `verify rejects the wrong password`() {
        val hash = hasher.hash("secret123")
        assertFalse(hasher.verify("secret124", hash))
        assertFalse(hasher.verify("", hash))
        assertFalse(hasher.verify("SECRET123", hash), "hashing must be case sensitive")
    }

    @Test
    fun `same password hashes differently every time`() {
        // Proves a unique salt per hash: two users sharing a password must not share
        // a stored value, or one cracked hash would expose both.
        val first = hasher.hash("secret123")
        val second = hasher.hash("secret123")

        assertTrue(first != second, "bcrypt must salt each hash")
        assertTrue(hasher.verify("secret123", first))
        assertTrue(hasher.verify("secret123", second))
    }

    @Test
    fun `verify returns false for a malformed stored hash`() {
        // A corrupt row must deny access, not raise a 500.
        assertFalse(hasher.verify("secret123", "not-a-bcrypt-hash"))
        assertFalse(hasher.verify("secret123", ""))
    }

    @Test
    fun `hash rejects input beyond bcrypt's 72 byte limit`() {
        val tooLong = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { hasher.hash(tooLong) }
    }

    // ---------------- JwtUtil ----------------

    @Test
    fun `issued token verifies and carries its claims`() {
        val jwt = jwtAt(now)
        val userId = UUID.randomUUID()

        val token = jwt.generateToken(userId, "user@example.com", Role.ADMIN)
        val decoded = jwt.verifier.verify(token)
        val caller = jwt.extractUser(decoded)

        assertNotNull(caller)
        assertEquals(userId, caller.userId)
        assertEquals("user@example.com", caller.email)
        assertEquals(Role.ADMIN, caller.role)
        assertTrue(caller.isAdmin)
    }

    @Test
    fun `a tampered token is rejected`() {
        val jwt = jwtAt(now)
        val token = jwt.generateToken(UUID.randomUUID(), "user@example.com", Role.USER)

        // Flip a character in the payload segment; the signature no longer matches.
        val parts = token.split(".")
        val tamperedPayload = parts[1].dropLast(2) + if (parts[1].endsWith("A")) "BB" else "AA"
        val tampered = "${parts[0]}.$tamperedPayload.${parts[2]}"

        assertFailsWith<Exception> { jwt.verifier.verify(tampered) }
    }

    @Test
    fun `a token signed with another secret is rejected`() {
        val token = jwtAt(now).generateToken(UUID.randomUUID(), "user@example.com", Role.USER)

        val foreign = JwtUtil(
            jwtConfig.copy(secret = "a-completely-different-secret-value-here"),
            TimeProvider.fixedAt(now),
        )

        assertFailsWith<Exception> { foreign.verifier.verify(token) }
    }

    @Test
    fun `an expired token is rejected`() {
        // Issued 24h ago with a 24h lifetime, verified now.
        val issuedEarlier = jwtAt(now.minusHours(25))
        val token = issuedEarlier.generateToken(UUID.randomUUID(), "user@example.com", Role.USER)

        val verifyingNow = jwtAt(now)
        assertFailsWith<Exception> { verifyingNow.verifier.verify(token) }
    }

    @Test
    fun `a token still inside its window is accepted`() {
        val issued = jwtAt(now.minusHours(23))
        val token = issued.generateToken(UUID.randomUUID(), "user@example.com", Role.USER)

        val decoded = jwtAt(now).verifier.verify(token)
        assertNotNull(jwtAt(now).extractUser(decoded))
    }

    @Test
    fun `a token with an unknown role is not trusted`() {
        // Defensive: an unrecognised role must not be silently downgraded to USER
        // and accepted; the token is rejected outright.
        val jwt = jwtAt(now)
        val handCrafted = com.auth0.jwt.JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withClaim(JwtUtil.CLAIM_USER_ID, UUID.randomUUID().toString())
            .withClaim(JwtUtil.CLAIM_EMAIL, "user@example.com")
            .withClaim(JwtUtil.CLAIM_ROLE, "SUPERADMIN")
            .withExpiresAt(java.util.Date.from(now.plusHours(1).toInstant()))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtConfig.secret))

        val decoded = jwt.verifier.verify(handCrafted)
        assertNull(jwt.extractUser(decoded), "unknown roles must be rejected")
    }

    @Test
    fun `a token missing required claims is not trusted`() {
        val jwt = jwtAt(now)
        val noUserId = com.auth0.jwt.JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withClaim(JwtUtil.CLAIM_EMAIL, "user@example.com")
            .withClaim(JwtUtil.CLAIM_ROLE, "USER")
            .withExpiresAt(java.util.Date.from(now.plusHours(1).toInstant()))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtConfig.secret))

        assertNull(jwt.extractUser(jwt.verifier.verify(noUserId)))
    }

    @Test
    fun `a short secret is refused at construction`() {
        // Fail loudly at boot rather than signing tokens with a weak key.
        assertFailsWith<IllegalArgumentException> {
            jwtConfig.copy(secret = "too-short")
        }
    }

    @Test
    fun `expiresIn reflects the configured lifetime`() {
        assertEquals(24 * 3600L, jwtAt(now).expiresInSeconds)
        assertEquals(
            2 * 3600L,
            JwtUtil(jwtConfig.copy(expiryHours = 2), TimeProvider.fixedAt(now)).expiresInSeconds,
        )
    }
}
