package com.ticketbooking.service

import com.ticketbooking.dto.SeatMapResponse
import com.ticketbooking.dto.SeatMapSummary
import com.ticketbooking.dto.SeatResponse
import com.ticketbooking.dto.toMoneyString
import com.ticketbooking.model.Event
import com.ticketbooking.model.SeatStatus
import com.ticketbooking.repository.EventRepository
import com.ticketbooking.repository.EventSeatWithSeat
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.Validators
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Seat maps and seat pricing.
 *
 * Two rules live here and are relied on by the whole booking flow:
 *
 * 1. PRICING. A seat's price is the event's base price scaled by the seat tier's
 *    multiplier, rounded to 2 decimal places. Computed rather than stored, so
 *    changing an event's base price re-prices every unsold seat consistently.
 *
 * 2. LAZY EXPIRY. Status is reported via [com.ticketbooking.model.EventSeat.effectiveStatus],
 *    so a hold whose 24 hours have elapsed shows as AVAILABLE immediately - even if
 *    the background sweeper has not rewritten the row yet. Without this, a lapsed
 *    hold would keep a seat off the market until the next sweep.
 */
class SeatService(
    private val seats: SeatRepository,
    private val events: EventRepository,
    private val transactions: TransactionRunner,
    private val time: TimeProvider = TimeProvider.SYSTEM,
) {

    /**
     * Seat map for GET /api/events/{id}/seats.
     *
     * @param includeUnpublished admin-only; public callers must not see draft seat maps.
     * @throws NotFoundException if the event does not exist or is not visible.
     */
    fun getSeatMap(rawEventId: String?, includeUnpublished: Boolean = false): SeatMapResponse {
        val eventId = Validators.parseUuid(rawEventId, "id")
        return getSeatMap(eventId, includeUnpublished)
    }

    fun getSeatMap(eventId: UUID, includeUnpublished: Boolean = false): SeatMapResponse {
        val now = time.nowOffset()

        val (event, rows) = transactions.inTransaction {
            val event = events.findById(eventId) ?: throw NotFoundException("Event not found")
            event to seats.findEventSeatMap(eventId)
        }

        if (!includeUnpublished && !event.isPublic) {
            // Same reasoning as the event detail endpoint: do not confirm that a
            // hidden event exists.
            throw NotFoundException("Event not found")
        }

        val seatResponses = rows.map { row -> row.toSeatResponse(event, now) }

        return SeatMapResponse(
            eventId = eventId.toString(),
            seats = seatResponses,
            summary = SeatMapSummary(
                total = seatResponses.size,
                available = seatResponses.count { it.status == SeatStatus.AVAILABLE.name },
                locked = seatResponses.count { it.status == SeatStatus.LOCKED.name },
                booked = seatResponses.count { it.status == SeatStatus.BOOKED.name },
            ),
        )
    }

    private fun EventSeatWithSeat.toSeatResponse(
        event: Event,
        now: java.time.OffsetDateTime,
    ) = SeatResponse(
        eventSeatId = eventSeat.id.toString(),
        row = seat.seatRow,
        number = seat.seatNumber,
        type = seat.seatType.name,
        price = priceFor(event.basePrice, seat.priceMultiplier).toMoneyString(),
        // effectiveStatus, not status: an expired hold must read as AVAILABLE.
        status = eventSeat.effectiveStatus(now).name,
    )

    companion object {
        /**
         * A seat's price for an event.
         *
         * HALF_UP at 2 decimal places matches how money is normally rounded and
         * keeps the value consistent with the NUMERIC(10,2) column it is stored
         * into when a booking is made.
         */
        fun priceFor(basePrice: BigDecimal, priceMultiplier: BigDecimal): BigDecimal =
            basePrice.multiply(priceMultiplier).setScale(2, RoundingMode.HALF_UP)
    }
}
