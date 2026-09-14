package com.ticketbooking.repository.impl

import com.ticketbooking.model.Role
import com.ticketbooking.model.User
import com.ticketbooking.model.Users
import com.ticketbooking.repository.UserRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDateTime
import java.util.UUID

/**
 * PostgreSQL implementation of [UserRepository].
 *
 * Every method must be called inside a
 * [com.ticketbooking.repository.TransactionRunner.inTransaction] block - Exposed
 * resolves the active transaction from the calling context and throws without one.
 *
 * Emails are stored already lower-cased by the service layer, so lookups compare
 * directly and can use the unique index rather than forcing a sequential scan
 * through `lower(email)`.
 */
class PostgresUserRepository : UserRepository {

    override fun findById(id: UUID): User? =
        Users.selectAll()
            .where { Users.id eq id }
            .limit(1)
            .singleOrNull()
            ?.toUser()

    override fun findByEmail(email: String): User? =
        Users.selectAll()
            .where { Users.email eq email.trim().lowercase() }
            .limit(1)
            .singleOrNull()
            ?.toUser()

    override fun existsByEmail(email: String): Boolean =
        Users.selectAll()
            .where { Users.email eq email.trim().lowercase() }
            .limit(1)
            .empty()
            .not()

    override fun create(
        id: UUID,
        name: String,
        email: String,
        passwordHash: String,
        role: Role,
        createdAt: LocalDateTime,
    ): User {
        Users.insert {
            it[Users.id] = id
            it[Users.name] = name
            it[Users.email] = email.trim().lowercase()
            it[Users.passwordHash] = passwordHash
            it[Users.role] = role.name
            it[Users.createdAt] = createdAt
        }
        return User(
            id = id,
            name = name,
            email = email.trim().lowercase(),
            passwordHash = passwordHash,
            role = role,
            createdAt = createdAt,
        )
    }

    override fun countAll(): Long = Users.selectAll().count()

    private fun ResultRow.toUser() = User(
        id = this[Users.id],
        name = this[Users.name],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        role = Role.from(this[Users.role]),
        createdAt = this[Users.createdAt],
    )
}
