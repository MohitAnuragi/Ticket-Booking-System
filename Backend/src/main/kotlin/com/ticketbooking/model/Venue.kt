package com.ticketbooking.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.util.UUID

/** `venues` table - a physical location that hosts events and owns a seat layout. */
object Venues : Table("venues") {
    val id = javaUUID("id")
    val name = varchar("name", 160)
    val address = text("address").nullable()
    val city = varchar("city", 100)
    val totalCapacity = integer("total_capacity")

    override val primaryKey = PrimaryKey(id)
}

/**
 * A venue.
 *
 * [totalCapacity] is the declared capacity supplied when the venue is created.
 * The authoritative seat count is the number of rows in `seats`; the two are
 * reconciled when a seat layout is generated.
 */
data class Venue(
    val id: UUID,
    val name: String,
    val address: String?,
    val city: String,
    val totalCapacity: Int,
)
