package com.ticketbooking.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Input validation. Each failure must report the offending `field` so the frontend
 * can highlight the right input, per the spec 6.5 error envelope.
 */
class ValidatorsTest {

    @Test
    fun `name is trimmed and length checked`() {
        assertEquals("Mohit", Validators.validateName("  Mohit  "))

        assertEquals("name", assertFailsWith<ValidationException> { Validators.validateName(null) }.field)
        assertFailsWith<ValidationException> { Validators.validateName("") }
        assertFailsWith<ValidationException> { Validators.validateName("   ") }
        assertFailsWith<ValidationException> { Validators.validateName("A") }
        assertFailsWith<ValidationException> {
            Validators.validateName("a".repeat(Validators.MAX_NAME_LENGTH + 1))
        }
    }

    @Test
    fun `email is normalised to lower case`() {
        // One canonical form is what makes "Bob@X.com" and "bob@x.com" the same account.
        assertEquals("bob@example.com", Validators.validateEmail("  Bob@Example.COM "))
    }

    @Test
    fun `email rejects malformed addresses`() {
        listOf(
            null, "", "   ", "no-at-sign", "@no-local.com", "no-domain@",
            "no@tld", "spaces in@example.com", "double@@example.com",
        ).forEach { candidate ->
            assertFailsWith<ValidationException>("should reject: $candidate") {
                Validators.validateEmail(candidate)
            }
        }
    }

    @Test
    fun `email accepts realistic addresses`() {
        listOf(
            "a@b.co", "first.last@example.com", "user+tag@example.co.uk",
            "user_name@sub.domain.org", "123@numbers.io",
        ).forEach { candidate ->
            Validators.validateEmail(candidate)
        }
    }

    @Test
    fun `password enforces a minimum length`() {
        Validators.validatePassword("secret123")

        assertEquals(
            "password",
            assertFailsWith<ValidationException> { Validators.validatePassword("short") }.field,
        )
        assertFailsWith<ValidationException> { Validators.validatePassword(null) }
        assertFailsWith<ValidationException> { Validators.validatePassword("") }
        assertFailsWith<ValidationException> {
            Validators.validatePassword("a".repeat(Validators.MIN_PASSWORD_LENGTH - 1))
        }
    }

    @Test
    fun `password is not trimmed`() {
        // Stripping spaces would silently change the credential and break a later login.
        val withSpaces = "  spaces  "
        assertEquals(withSpaces, Validators.validatePassword(withSpaces))
    }

    @Test
    fun `password beyond bcrypt's limit is rejected rather than silently truncated`() {
        val tooLong = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES + 1)
        assertFailsWith<ValidationException> { Validators.validatePassword(tooLong) }
    }

    @Test
    fun `uuid parsing reports a 400 rather than crashing`() {
        val id = java.util.UUID.randomUUID()
        assertEquals(id, Validators.parseUuid(id.toString(), "eventId"))

        val error = assertFailsWith<ValidationException> { Validators.parseUuid("not-a-uuid", "eventId") }
        assertEquals("eventId", error.field)
        assertFailsWith<ValidationException> { Validators.parseUuid(null, "eventId") }
    }

    @Test
    fun `requireText enforces presence and bounds`() {
        assertEquals("Arena", Validators.requireText(" Arena ", "name", max = 10))
        assertFailsWith<ValidationException> { Validators.requireText("", "name", max = 10) }
        assertFailsWith<ValidationException> { Validators.requireText("very long value", "name", max = 5) }
    }

    @Test
    fun `optionalText allows absence but still bounds length`() {
        assertNull(Validators.optionalText(null, "description", max = 10))
        assertNull(Validators.optionalText("   ", "description", max = 10))
        assertEquals("hello", Validators.optionalText(" hello ", "description", max = 10))
        assertFailsWith<ValidationException> {
            Validators.optionalText("way too long", "description", max = 5)
        }
    }

    @Test
    fun `numeric guards reject zero negative and out of range`() {
        assertEquals(5, Validators.requirePositive(5, "seatsPerRow"))
        assertFailsWith<ValidationException> { Validators.requirePositive(0, "seatsPerRow") }
        assertFailsWith<ValidationException> { Validators.requirePositive(-1, "seatsPerRow") }
        assertFailsWith<ValidationException> { Validators.requirePositive(null, "seatsPerRow") }

        assertEquals(3, Validators.requireInRange(3, "count", 1, 10))
        assertFailsWith<ValidationException> { Validators.requireInRange(11, "count", 1, 10) }
        assertFailsWith<ValidationException> { Validators.requireInRange(0, "count", 1, 10) }
    }
}
