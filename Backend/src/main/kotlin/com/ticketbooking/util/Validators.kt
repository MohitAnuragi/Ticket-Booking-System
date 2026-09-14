package com.ticketbooking.util

import java.util.UUID

/**
 * Request validation, centralised so every endpoint rejects bad input the same way
 * and always produces the `{ error, message, field }` envelope from spec 6.5.
 *
 * Each function returns the NORMALISED value (trimmed, and lower-cased for email)
 * so callers use the cleaned version rather than the raw input.
 */
object Validators {

    // Intentionally pragmatic rather than RFC-complete: catches real typos without
    // rejecting unusual but legitimate addresses.
    private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")

    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_NAME_LENGTH = 120
    const val MAX_EMAIL_LENGTH = 160

    /** @return the trimmed name. */
    fun validateName(raw: String?, field: String = "name"): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) throw ValidationException("name is required", field)
        if (name.length < 2) throw ValidationException("name must be at least 2 characters", field)
        if (name.length > MAX_NAME_LENGTH) {
            throw ValidationException("name must be at most $MAX_NAME_LENGTH characters", field)
        }
        return name
    }

    /**
     * @return the email lower-cased and trimmed. Storing one canonical form is what
     *         makes "Bob@x.com" and "bob@x.com" the same account.
     */
    fun validateEmail(raw: String?, field: String = "email"): String {
        val email = raw?.trim()?.lowercase().orEmpty()
        if (email.isEmpty()) throw ValidationException("email is required", field)
        if (email.length > MAX_EMAIL_LENGTH) {
            throw ValidationException("email must be at most $MAX_EMAIL_LENGTH characters", field)
        }
        if (!EMAIL_PATTERN.matches(email)) {
            throw ValidationException("email is not a valid address", field)
        }
        return email
    }

    /**
     * Passwords are not trimmed - leading and trailing spaces are legitimate
     * characters and silently stripping them would break a later login.
     */
    fun validatePassword(raw: String?, field: String = "password"): String {
        val password = raw.orEmpty()
        if (password.isEmpty()) throw ValidationException("password is required", field)
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ValidationException(
                "password must be at least $MIN_PASSWORD_LENGTH characters",
                field,
            )
        }
        if (password.toByteArray(Charsets.UTF_8).size > PasswordHasher.MAX_PASSWORD_BYTES) {
            // BCrypt ignores anything past 72 bytes, which would make the extra
            // characters meaningless security theatre. Better to say so.
            throw ValidationException(
                "password must be at most ${PasswordHasher.MAX_PASSWORD_BYTES} bytes",
                field,
            )
        }
        return password
    }

    /** Parses a path or body UUID, reporting a 400 rather than a 500 on garbage. */
    fun parseUuid(raw: String?, field: String): UUID {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) throw ValidationException("$field is required", field)
        return try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw ValidationException("$field must be a valid UUID", field)
        }
    }

    /** @return the trimmed value, guaranteed non-blank and within [max]. */
    fun requireText(raw: String?, field: String, max: Int, min: Int = 1): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) throw ValidationException("$field is required", field)
        if (value.length < min) {
            throw ValidationException("$field must be at least $min characters", field)
        }
        if (value.length > max) {
            throw ValidationException("$field must be at most $max characters", field)
        }
        return value
    }

    /** @return the trimmed value, or null when absent or blank. */
    fun optionalText(raw: String?, field: String, max: Int): String? {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return null
        if (value.length > max) {
            throw ValidationException("$field must be at most $max characters", field)
        }
        return value
    }

    fun requirePositive(value: Int?, field: String): Int {
        if (value == null) throw ValidationException("$field is required", field)
        if (value <= 0) throw ValidationException("$field must be greater than zero", field)
        return value
    }

    fun requireInRange(value: Int?, field: String, min: Int, max: Int): Int {
        if (value == null) throw ValidationException("$field is required", field)
        if (value < min || value > max) {
            throw ValidationException("$field must be between $min and $max", field)
        }
        return value
    }
}
