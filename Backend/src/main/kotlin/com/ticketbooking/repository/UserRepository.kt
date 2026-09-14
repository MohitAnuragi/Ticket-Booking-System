package com.ticketbooking.repository

import com.ticketbooking.model.Role
import com.ticketbooking.model.User
import java.time.LocalDateTime
import java.util.UUID

/**
 * Data access for `users`. No business logic: hashing, validation and role rules
 * belong to the service layer.
 *
 * Implemented against PostgreSQL in production and by an in-memory fake in tests,
 * which is what allows [com.ticketbooking.service.AuthService] to be unit-tested
 * with no database.
 */
interface UserRepository {

    /** @return the user with this id, or null. */
    fun findById(id: UUID): User?

    /**
     * @param email matched case-insensitively, since email is treated as
     *        case-insensitive for login while the original casing is preserved.
     */
    fun findByEmail(email: String): User?

    /** Cheaper than [findByEmail] when only the duplicate check matters. */
    fun existsByEmail(email: String): Boolean

    /**
     * Inserts a new user.
     *
     * The caller supplies [id] and [createdAt] so identifiers and timestamps stay
     * deterministic and testable rather than being invented by the database.
     *
     * @param passwordHash an already-hashed password; implementations never hash.
     * @return the created user.
     */
    fun create(
        id: UUID,
        name: String,
        email: String,
        passwordHash: String,
        role: Role,
        createdAt: LocalDateTime,
    ): User

    /** Total accounts; used by the bootstrap-admin check. */
    fun countAll(): Long
}
