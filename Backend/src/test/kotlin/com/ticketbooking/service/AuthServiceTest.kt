package com.ticketbooking.service

import com.ticketbooking.dto.LoginRequest
import com.ticketbooking.dto.RegisterRequest
import com.ticketbooking.model.Role
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.FakeUserRepository
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.JwtConfig
import com.ticketbooking.util.JwtUtil
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.PasswordHasher
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.UnauthorizedException
import com.ticketbooking.util.ValidationException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [AuthService] against an in-memory repository - no database involved.
 *
 * Covers the security-relevant paths: no privilege escalation via registration,
 * duplicate emails rejected, and login failures that do not reveal whether an
 * address is registered.
 */
class AuthServiceTest {

    // Anchored to real "now": tokens issued here are verified by JWTVerifier, which
    // uses the system clock (java-jwt 4.6 removed clock injection).
    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
    private val time = TimeProvider.fixedAt(now)

    private val users = FakeUserRepository()
    private val hasher = PasswordHasher(cost = 4)
    private val jwt = JwtUtil(
        JwtConfig(
            secret = "test-secret-that-is-long-enough-for-hmac256",
            issuer = "ticket-booking-test",
            audience = "ticket-booking-test-users",
            realm = "test",
            expiryHours = 24,
        ),
        time,
    )

    private val service = AuthService(
        users = users,
        transactions = DirectTransactionRunner(),
        hasher = hasher,
        jwt = jwt,
        time = time,
    )

    private fun register(
        name: String = "Mohit",
        email: String = "mohit@example.com",
        password: String = "secret123",
    ) = service.register(RegisterRequest(name, email, password))

    // ---------------- Registration ----------------

    @Test
    fun `register creates a USER and never stores the plaintext password`() {
        val response = register()

        assertEquals("Mohit", response.name)
        assertEquals("mohit@example.com", response.email)
        assertEquals("USER", response.role)

        val stored = users.all().single()
        assertEquals(Role.USER, stored.role)
        assertTrue(stored.passwordHash != "secret123", "password must not be stored in plaintext")
        assertTrue(hasher.verify("secret123", stored.passwordHash), "stored hash must verify")
        assertEquals(now.toLocalDateTime(), stored.createdAt)
    }

    @Test
    fun `register always assigns USER so a client cannot self-promote to ADMIN`() {
        // RegisterRequest has no role field at all, which is the point: even a client
        // that sends one cannot influence the outcome.
        val response = register(email = "someone@example.com")
        assertEquals("USER", response.role)
        assertTrue(users.all().none { it.isAdmin })
    }

    @Test
    fun `register normalises the email`() {
        val response = register(email = "  Mohit@Example.COM ")
        assertEquals("mohit@example.com", response.email)
    }

    @Test
    fun `register rejects a duplicate email regardless of casing`() {
        register(email = "mohit@example.com")

        val error = assertFailsWith<ConflictException> {
            register(name = "Someone Else", email = "MOHIT@example.com")
        }
        assertEquals("EMAIL_ALREADY_REGISTERED", error.errorCode)
        assertEquals(1, users.all().size, "the duplicate must not have been inserted")
    }

    @Test
    fun `register validates its input before touching the repository`() {
        assertFailsWith<ValidationException> { register(name = "") }
        assertFailsWith<ValidationException> { register(email = "not-an-email") }
        assertFailsWith<ValidationException> { register(password = "short") }

        assertEquals(0, users.all().size, "nothing should be persisted on invalid input")
    }

    // ---------------- Login ----------------

    @Test
    fun `login returns a usable token for correct credentials`() {
        val registered = register()

        val auth = service.login(LoginRequest("mohit@example.com", "secret123"))

        assertEquals(registered.id, auth.user.id)
        assertEquals(24 * 3600L, auth.expiresIn)

        val caller = jwt.extractUser(jwt.verifier.verify(auth.token))
        assertNotNull(caller)
        assertEquals(UUID.fromString(registered.id), caller.userId)
        assertEquals(Role.USER, caller.role)
    }

    @Test
    fun `login accepts a differently cased email`() {
        register(email = "mohit@example.com")
        service.login(LoginRequest("MOHIT@EXAMPLE.COM", "secret123"))
    }

    @Test
    fun `login rejects a wrong password`() {
        register()
        assertFailsWith<UnauthorizedException> {
            service.login(LoginRequest("mohit@example.com", "wrong-password"))
        }
    }

    @Test
    fun `unknown email and wrong password fail identically`() {
        register()

        val unknownEmail = assertFailsWith<UnauthorizedException> {
            service.login(LoginRequest("nobody@example.com", "secret123"))
        }
        val wrongPassword = assertFailsWith<UnauthorizedException> {
            service.login(LoginRequest("mohit@example.com", "wrong-password"))
        }

        // Identical messages mean the endpoint cannot be used to enumerate accounts.
        assertEquals(unknownEmail.message, wrongPassword.message)
        assertEquals(unknownEmail.errorCode, wrongPassword.errorCode)
    }

    @Test
    fun `login rejects a missing password without a repository lookup`() {
        register()
        assertFailsWith<UnauthorizedException> {
            service.login(LoginRequest("mohit@example.com", ""))
        }
        assertFailsWith<UnauthorizedException> {
            service.login(LoginRequest("mohit@example.com", null))
        }
    }

    @Test
    fun `login validates the email format`() {
        assertFailsWith<ValidationException> { service.login(LoginRequest("bad", "secret123")) }
    }

    // ---------------- Current user ----------------

    @Test
    fun `findCurrentUser returns the live account`() {
        val registered = register()

        val current = service.findCurrentUser(UUID.fromString(registered.id))

        assertEquals(registered.id, current.id)
        assertEquals("mohit@example.com", current.email)
    }

    @Test
    fun `findCurrentUser 404s when the account is gone`() {
        // A token can outlive the account it names; that must be a clean 404.
        assertFailsWith<NotFoundException> { service.findCurrentUser(UUID.randomUUID()) }
    }

    // ---------------- Bootstrap admin ----------------

    @Test
    fun `bootstrap creates an ADMIN account`() {
        val created = service.ensureBootstrapAdmin(
            email = "admin@ticketbooking.local",
            password = "admin-password",
            name = "Platform Admin",
        )

        assertTrue(created)
        val admin = users.all().single()
        assertEquals(Role.ADMIN, admin.role)
        assertEquals("admin@ticketbooking.local", admin.email)
        assertTrue(hasher.verify("admin-password", admin.passwordHash))
    }

    @Test
    fun `bootstrap is idempotent`() {
        service.ensureBootstrapAdmin("admin@ticketbooking.local", "admin-password", "Admin")
        val secondRun = service.ensureBootstrapAdmin("admin@ticketbooking.local", "different-password", "Admin")

        assertTrue(!secondRun, "a second run must not create another admin")
        assertEquals(1, users.all().size)
        // The original password must survive - restarting the server must not rotate it.
        assertTrue(hasher.verify("admin-password", users.all().single().passwordHash))
    }

    @Test
    fun `bootstrap never promotes an existing non-admin account`() {
        register(email = "admin@ticketbooking.local")

        val created = service.ensureBootstrapAdmin("admin@ticketbooking.local", "admin-password", "Admin")

        assertTrue(!created)
        assertEquals(Role.USER, users.all().single().role, "an existing user must not be silently promoted")
    }

    @Test
    fun `bootstrap is skipped when not configured`() {
        assertTrue(!service.ensureBootstrapAdmin(null, "password", "Admin"))
        assertTrue(!service.ensureBootstrapAdmin("", "password", "Admin"))
        assertTrue(!service.ensureBootstrapAdmin("admin@example.com", null, "Admin"))
        assertTrue(!service.ensureBootstrapAdmin("admin@example.com", "", "Admin"))

        assertEquals(0, users.all().size)
    }

    @Test
    fun `bootstrap rejects a weak configured password`() {
        assertFailsWith<ValidationException> {
            service.ensureBootstrapAdmin("admin@example.com", "short", "Admin")
        }
    }

    @Test
    fun `an admin created by bootstrap can log in and gets an ADMIN token`() {
        service.ensureBootstrapAdmin("admin@ticketbooking.local", "admin-password", "Admin")

        val auth = service.login(LoginRequest("admin@ticketbooking.local", "admin-password"))
        val caller = jwt.extractUser(jwt.verifier.verify(auth.token))

        assertNotNull(caller)
        assertEquals(Role.ADMIN, caller.role)
        assertTrue(caller.isAdmin)
    }
}
