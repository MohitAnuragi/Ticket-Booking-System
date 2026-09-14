package com.ticketbooking.dto

import kotlinx.serialization.Serializable

/**
 * One seat in a seat map (spec section 6.2).
 *
 * [eventSeatId] is what the client sends back when holding seats - the per-event
 * row, not the physical seat, since availability is per event.
 *
 * [price] is the resolved price for THIS seat at THIS event
 * (event base price x the seat's tier multiplier), so the frontend never has to
 * recompute money.
 */
@Serializable
data class SeatResponse(
    val eventSeatId: String,
    val row: String,
    val number: Int,
    val type: String,
    val price: String,
    val status: String,
)

/**
 * Seat map for an event.
 *
 * [summary] lets the UI show "42 of 120 available" without counting client-side.
 */
@Serializable
data class SeatMapResponse(
    val eventId: String,
    val seats: List<SeatResponse>,
    val summary: SeatMapSummary,
)

/** Availability totals for a seat map. */
@Serializable
data class SeatMapSummary(
    val total: Int,
    val available: Int,
    val locked: Int,
    val booked: Int,
)
