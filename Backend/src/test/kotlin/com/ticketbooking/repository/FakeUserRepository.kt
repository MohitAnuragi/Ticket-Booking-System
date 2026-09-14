package com.ticketbooking.repository

import com.ticketbooking.model.Role
import com.ticketbooking.model.User
import java.time.LocalDateTime
import java.util.UUID

/**
 * In-memory [UserRepository] for unit tests.
 *
 * This is what the repository-interface design buys: [com.ticketbooking.service.AuthService]
 * can be tested completely - including duplicate-email and login failure paths -
 * with no PostgreSQL, no Docker and no network.
 *
 * It mirrors the real implementation's observable behaviour, in particular
 * case-insensitive email lookup, so tests are not passing against easier rules
 * than production enforces.
 */
class FakeUserRepository(
    initial: List<User> = emptyList(),
) : UserRepository {

    private val store = linkedMapOf<UUID, User>()

    /** Set to simulate a database failure and assert the caller's error handling. */
    var failOnCreate: Boolean = false

    init {
        initial.forEach { store[it.id] = it }
    }

    override fun findById(id: UUID): User? = store[id]

    override fun findByEmail(email: String): User? {
        val needle = email.trim().lowercase()
        return store.values.firstOrNull { it.email.lowercase() == needle }
    }

    override fun existsByEmail(email: String): Boolean = findByEmail(email) != null

    override fun create(
        id: UUID,
        name: String,
        email: String,
        passwordHash: String,
        role: Role,
        createdAt: LocalDateTime,
    ): User {
        if (failOnCreate) throw IllegalStateException("simulated database failure")

        val normalisedEmail = email.trim().lowercase()
        // Mirrors the UNIQUE(email) constraint, so a test cannot create a duplicate
        // that the real database would have rejected.
        require(findByEmail(normalisedEmail) == null) { "duplicate email $normalisedEmail" }

        val user = User(
            id = id,
            name = name,
            email = normalisedEmail,
            passwordHash = passwordHash,
            role = role,
            createdAt = createdAt,
        )
        store[id] = user
        return user
    }

    override fun countAll(): Long = store.size.toLong()

    // ---- test helpers ----

    /** All stored users, in insertion order. */
    fun all(): List<User> = store.values.toList()
}
