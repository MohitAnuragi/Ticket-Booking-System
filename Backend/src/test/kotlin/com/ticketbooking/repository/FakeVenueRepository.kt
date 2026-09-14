package com.ticketbooking.repository

import com.ticketbooking.model.Venue
import java.util.UUID

/** In-memory [VenueRepository] for unit tests. */
class FakeVenueRepository : VenueRepository {

    private val store = linkedMapOf<UUID, Venue>()

    override fun findById(id: UUID): Venue? = store[id]

    override fun findAll(): List<Venue> = store.values.sortedBy { it.name }

    override fun create(
        id: UUID,
        name: String,
        address: String?,
        city: String,
        totalCapacity: Int,
    ): Venue {
        val venue = Venue(id, name, address, city, totalCapacity)
        store[id] = venue
        return venue
    }

    override fun updateCapacity(id: UUID, totalCapacity: Int) {
        store[id]?.let { store[id] = it.copy(totalCapacity = totalCapacity) }
    }

    override fun countAll(): Long = store.size.toLong()

    // ---- test helpers ----

    fun put(venue: Venue) {
        store[venue.id] = venue
    }

    fun all(): List<Venue> = store.values.toList()
}
