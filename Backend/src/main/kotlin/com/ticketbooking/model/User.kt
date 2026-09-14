package com.ticketbooking.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime
import java.util.UUID

/** Account roles. Public registration only ever produces [USER]. */
enum class Role {
    USER,
    ADMIN,
    ;

    companion object {
        /** Parses a stored role, defaulting to [USER] for anything unrecognised. */
        fun from(raw: String): Role = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: USER
    }
}

/**
 * `users` table.
 *
 * `created_at` is a timezone-naive TIMESTAMP in the schema; the application
 * consistently reads and writes it as UTC (see [com.ticketbooking.util.TimeProvider]).
 */
object Users : Table("users") {
    val id = javaUUID("id")
    val name = varchar("name", 120)
    val email = varchar("email", 160)
    val passwordHash = text("password_hash")
    val role = varchar("role", 20)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * A registered account.
 *
 * Carries [passwordHash], never a plaintext password, so an accidental log of
 * this object cannot leak a credential.
 */
data class User(
    val id: UUID,
    val name: String,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val createdAt: LocalDateTime,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
}
