package com.ticketbooking.repository.impl

import com.ticketbooking.model.Venue
import com.ticketbooking.model.Venues
import com.ticketbooking.repository.VenueRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * PostgreSQL implementation of [VenueRepository].
 * Must be called inside a transaction.
 */
class PostgresVenueRepository : VenueRepository {

    override fun findById(id: UUID): Venue? =
        Venues.selectAll()
            .where { Venues.id eq id }
            .limit(1)
            .singleOrNull()
            ?.toVenue()

    override fun findAll(): List<Venue> =
        Venues.selectAll()
            .orderBy(Venues.name to SortOrder.ASC)
            .map { it.toVenue() }

    override fun create(
        id: UUID,
        name: String,
        address: String?,
        city: String,
        totalCapacity: Int,
    ): Venue {
        Venues.insert {
            it[Venues.id] = id
            it[Venues.name] = name
            it[Venues.address] = address
            it[Venues.city] = city
            it[Venues.totalCapacity] = totalCapacity
        }
        return Venue(id, name, address, city, totalCapacity)
    }

    override fun updateCapacity(id: UUID, totalCapacity: Int) {
        Venues.update({ Venues.id eq id }) { it[Venues.totalCapacity] = totalCapacity }
    }

    override fun countAll(): Long = Venues.selectAll().count()

    private fun ResultRow.toVenue() = Venue(
        id = this[Venues.id],
        name = this[Venues.name],
        address = this[Venues.address],
        city = this[Venues.city],
        totalCapacity = this[Venues.totalCapacity],
    )
}
