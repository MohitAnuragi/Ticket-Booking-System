package com.ticketbooking.service

import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import com.ticketbooking.model.SeatStatus
import com.ticketbooking.model.SeatType
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.EventWithVenue
import com.ticketbooking.repository.FakeEventRepository
import com.ticketbooking.repository.FakeSeatRepository
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.ValidationException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [SeatService]: seat pricing and the lazy-expiry status rule.
 *
 * The expiry assertions here are the ones that matter most - if an elapsed hold
 * did not read as AVAILABLE, seats would silently stay unsellable for up to a full
 * sweep interval.
 */
class SeatServiceTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)
    private val eventId = UUID.randomUUID()
    private val venueId = UUID.randomUUID()

    private val seats = FakeSeatRepository()
    private val events = FakeEventRepository()
    private val service = SeatService(
        seats = seats,
        events = events,
        transactions = DirectTransactionRunner(),
        time = TimeProvider.fixedAt(now),
    )

    private fun givenEvent(
        basePrice: String = "1000.00",
        status: EventStatus = EventStatus.PUBLISHED,
    ) {
        events.put(
            EventWithVenue(
                event = Event(
                    id = eventId,
                    venueId = venueId,
                    title = "Coldplay Live",
                    description = null,
                    category = "Concert",
                    startTime = LocalDateTime.of(2026, 10, 1, 19, 0),
                    endTime = LocalDateTime.of(2026, 10, 1, 22, 0),
                    basePrice = BigDecimal(basePrice),
                    status = status,
                    posterUrl = null,
                ),
                venueName = "NSCI Dome",
                venueCity = "Mumbai",
                venueAddress = "1 Test Road",
            ),
        )
    }

    // ---------------- Pricing ----------------

    @Test
    fun `seat price is base price times the tier multiplier`() {
        givenEvent(basePrice = "1500.00")
        seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.PREMIUM, BigDecimal("1.50"))

        val seat = service.getSeatMap(eventId).seats.single()

        // 1500.00 x 1.50 = 2250.00, matching the spec's worked example.
        assertEquals("2250.00", seat.price)
        assertEquals("PREMIUM", seat.type)
    }

    @Test
    fun `prices are rounded to two decimals half up`() {
        givenEvent(basePrice = "999.99")
        seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.VIP, BigDecimal("1.25"))

        // 999.99 x 1.25 = 1249.9875 -> 1249.99
        assertEquals("1249.99", service.getSeatMap(eventId).seats.single().price)
    }

    @Test
    fun `price always carries two decimal places`() {
        givenEvent(basePrice = "1000.00")
        seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.REGULAR, BigDecimal("1.00"))

        // "1000.00", never "1000" or "1000.0000" - the client renders this directly.
        assertEquals("1000.00", service.getSeatMap(eventId).seats.single().price)
    }

    @Test
    fun `different tiers price independently from one base price`() {
        givenEvent(basePrice = "1000.00")
        seats.addAvailableSeat(eventId, venueId, "A", 1, SeatType.REGULAR, BigDecimal("1.00"))
        seats.addAvailableSeat(eventId, venueId, "B", 1, SeatType.PREMIUM, BigDecimal("1.50"))
        seats.addAvailableSeat(eventId, venueId, "C", 1, SeatType.VIP, BigDecimal("2.50"))

        val byRow = service.getSeatMap(eventId).seats.associateBy { it.row }

        assertEquals("1000.00", byRow.getValue("A").price)
        assertEquals("1500.00", byRow.getValue("B").price)
        assertEquals("2500.00", byRow.getValue("C").price)
    }

    // ---------------- Status and lazy expiry ----------------

    @Test
    fun `available and booked seats report their stored status`() {
        givenEvent()
        seats.addAvailableSeat(eventId, venueId, "A", 1, status = SeatStatus.AVAILABLE)
        seats.addAvailableSeat(eventId, venueId, "A", 2, status = SeatStatus.BOOKED)

        val byNumber = service.getSeatMap(eventId).seats.associateBy { it.number }

        assertEquals("AVAILABLE", byNumber.getValue(1).status)
        assertEquals("BOOKED", byNumber.getValue(2).status)
    }

    @Test
    fun `a live hold reports LOCKED`() {
        givenEvent()
        seats.addAvailableSeat(
            eventId, venueId, "A", 1,
            status = SeatStatus.LOCKED,
            lockedBy = UUID.randomUUID(),
            lockExpiresAt = now.plusHours(23),
        )

        assertEquals("LOCKED", service.getSeatMap(eventId).seats.single().status)
    }

    @Test
    fun `an expired hold reports AVAILABLE before the sweeper runs`() {
        givenEvent()
        seats.addAvailableSeat(
            eventId, venueId, "A", 1,
            status = SeatStatus.LOCKED,
            lockedBy = UUID.randomUUID(),
            // Held 24h ago with a 24h TTL: one second past the deadline.
            lockExpiresAt = now.minusSeconds(1),
        )

        val seat = service.getSeatMap(eventId).seats.single()

        assertEquals(
            "AVAILABLE",
            seat.status,
            "a lapsed hold must not keep the seat off the market",
        )
        // The stored row is still LOCKED; only the reported status differs.
        assertEquals(SeatStatus.LOCKED, seats.findEventSeatMap(eventId).single().eventSeat.status)
    }

    @Test
    fun `a hold expiring exactly now reports AVAILABLE`() {
        givenEvent()
        seats.addAvailableSeat(
            eventId, venueId, "A", 1,
            status = SeatStatus.LOCKED,
            lockedBy = UUID.randomUUID(),
            lockExpiresAt = now,
        )

        assertEquals("AVAILABLE", service.getSeatMap(eventId).seats.single().status)
    }

    // ---------------- Summary and ordering ----------------

    @Test
    fun `summary counts each status and treats expired holds as available`() {
        givenEvent()
        seats.addAvailableSeat(eventId, venueId, "A", 1, status = SeatStatus.AVAILABLE)
        seats.addAvailableSeat(eventId, venueId, "A", 2, status = SeatStatus.BOOKED)
        seats.addAvailableSeat(
            eventId, venueId, "A", 3,
            status = SeatStatus.LOCKED, lockedBy = UUID.randomUUID(), lockExpiresAt = now.plusHours(5),
        )
        seats.addAvailableSeat(
            eventId, venueId, "A", 4,
            status = SeatStatus.LOCKED, lockedBy = UUID.randomUUID(), lockExpiresAt = now.minusHours(1),
        )

        val summary = service.getSeatMap(eventId).summary

        assertEquals(4, summary.total)
        assertEquals(2, summary.available, "seat 4's hold has lapsed, so it counts as available")
        assertEquals(1, summary.locked)
        assertEquals(1, summary.booked)
    }

    @Test
    fun `seats are ordered by row then number`() {
        givenEvent()
        seats.addAvailableSeat(eventId, venueId, "B", 1)
        seats.addAvailableSeat(eventId, venueId, "A", 2)
        seats.addAvailableSeat(eventId, venueId, "A", 1)

        val order = service.getSeatMap(eventId).seats.map { "${it.row}${it.number}" }

        assertEquals(listOf("A1", "A2", "B1"), order)
    }

    // ---------------- Visibility and errors ----------------

    @Test
    fun `seat map for a draft event is hidden from the public`() {
        givenEvent(status = EventStatus.DRAFT)
        seats.addAvailableSeat(eventId, venueId, "A", 1)

        assertFailsWith<NotFoundException> { service.getSeatMap(eventId) }
        // Admins can still inspect it before publishing.
        assertEquals(1, service.getSeatMap(eventId, includeUnpublished = true).seats.size)
    }

    @Test
    fun `seat map for an unknown event is a 404`() {
        assertFailsWith<NotFoundException> { service.getSeatMap(UUID.randomUUID()) }
    }

    @Test
    fun `a malformed event id is a validation error`() {
        val error = assertFailsWith<ValidationException> { service.getSeatMap("not-a-uuid") }
        assertEquals("id", error.field)
    }

    @Test
    fun `an event with no generated seats returns an empty map rather than failing`() {
        givenEvent()

        val map = service.getSeatMap(eventId)

        assertEquals(0, map.seats.size)
        assertEquals(0, map.summary.total)
    }
}
