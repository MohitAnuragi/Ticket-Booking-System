package com.ticketbooking.repository

import com.ticketbooking.model.Venue
import java.util.UUID

/** Data access for `venues`. */
interface VenueRepository {

    fun findById(id: UUID): Venue?

    /** All venues, ordered by name for stable admin listings. */
    fun findAll(): List<Venue>

    fun create(
        id: UUID,
        name: String,
        address: String?,
        city: String,
        totalCapacity: Int,
    ): Venue

    /**
     * Overwrites the declared capacity, used after a seat layout is generated so
     * the venue's stated capacity matches the seats that actually exist.
     */
    fun updateCapacity(id: UUID, totalCapacity: Int)

    fun countAll(): Long
}
