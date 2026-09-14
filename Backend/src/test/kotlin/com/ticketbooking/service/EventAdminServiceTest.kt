package com.ticketbooking.service

import com.ticketbooking.dto.CreateEventRequest
import com.ticketbooking.dto.UpdateEventRequest
import com.ticketbooking.model.EventStatus
import com.ticketbooking.model.SeatType
import com.ticketbooking.model.Venue
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.FakeEventRepository
import com.ticketbooking.repository.FakeSeatRepository
import com.ticketbooking.repository.FakeVenueRepository
import com.ticketbooking.repository.SeatSpec
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.ValidationException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [EventAdminService]: event creation, patching and cancellation.
 *
 * The headline assertion is that creating an event generates exactly one
 * `event_seats` row per venue seat - an event with an empty seat map would be
 * silently unbookable.
 */
class EventAdminServiceTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)

    private val events = FakeEventRepository()
    private val venues = FakeVenueRepository()
    private val seats = FakeSeatRepository()

    init {
        // Mirrors the real repository's join on `venues`, so created events report
        // their actual venue rather than a placeholder.
        events.venueResolver = { venues.findById(it) }
    }
    private val service = EventAdminService(
        events = events,
        venues = venues,
        seats = seats,
        transactions = DirectTransactionRunner(),
        time = TimeProvider.fixedAt(now),
    )

    /** A venue with [seatCount] seats already laid out. */
    private fun givenVenueWithSeats(seatCount: Int = 10): UUID {
        val venueId = UUID.randomUUID()
        venues.put(Venue(venueId, "NSCI Dome", "1 Road", "Mumbai", seatCount))
        seats.createSeats(
            venueId,
            (1..seatCount).map { SeatSpec("A", it, SeatType.REGULAR, BigDecimal("1.00")) },
        )
        return venueId
    }

    private fun givenEmptyVenue(): UUID {
        val venueId = UUID.randomUUID()
        venues.put(Venue(venueId, "Empty Hall", null, "Delhi", 0))
        return venueId
    }

    private fun request(
        venueId: UUID,
        title: String = "Coldplay Live",
        startTime: String = "2026-10-01T19:00:00",
        endTime: String = "2026-10-01T22:00:00",
        basePrice: String = "1500.00",
        status: String? = null,
        category: String? = "Concert",
    ) = CreateEventRequest(
        venueId = venueId.toString(),
        title = title,
        description = "An evening of live music",
        category = category,
        startTime = startTime,
        endTime = endTime,
        basePrice = basePrice,
        status = status,
        posterUrl = null,
    )

    // ---------------- Creation and seat generation ----------------

    @Test
    fun `creating an event generates one seat row per venue seat`() {
        val venueId = givenVenueWithSeats(seatCount = 12)

        val created = service.createEvent(request(venueId))

        assertEquals(12, created.seatsGenerated)
        val eventId = UUID.fromString(created.event.id)
        assertEquals(12L, seats.countEventSeats(eventId))
    }

    @Test
    fun `all generated seats start AVAILABLE`() {
        val venueId = givenVenueWithSeats(seatCount = 5)

        val created = service.createEvent(request(venueId))
        val eventId = UUID.fromString(created.event.id)

        val statuses = seats.findEventSeatMap(eventId).map { it.eventSeat.status.name }.distinct()
        assertEquals(listOf("AVAILABLE"), statuses)
    }

    @Test
    fun `created event echoes its details and venue`() {
        val venueId = givenVenueWithSeats()

        val detail = service.createEvent(request(venueId)).event

        assertEquals("Coldplay Live", detail.title)
        assertEquals("1500.00", detail.basePrice)
        assertEquals("2026-10-01T19:00:00", detail.startTime)
        assertEquals("PUBLISHED", detail.status)
        assertEquals("Mumbai", detail.venue.city)
    }

    @Test
    fun `events default to PUBLISHED but can be created as DRAFT`() {
        val venueId = givenVenueWithSeats()

        assertEquals("PUBLISHED", service.createEvent(request(venueId)).event.status)
        assertEquals(
            "DRAFT",
            service.createEvent(request(venueId, title = "Hidden", status = "DRAFT")).event.status,
        )
    }

    @Test
    fun `an event cannot be created as CANCELLED`() {
        val venueId = givenVenueWithSeats()

        val error = assertFailsWith<ValidationException> {
            service.createEvent(request(venueId, status = "CANCELLED"))
        }
        assertEquals("status", error.field)
    }

    @Test
    fun `a venue with no seat layout is refused`() {
        val venueId = givenEmptyVenue()

        // Otherwise the event would exist with an empty seat map and be unbookable.
        val error = assertFailsWith<ConflictException> { service.createEvent(request(venueId)) }
        assertEquals("VENUE_HAS_NO_SEATS", error.errorCode)
        assertEquals(0, events.countAll())
    }

    @Test
    fun `an unknown venue is a 404`() {
        assertFailsWith<NotFoundException> { service.createEvent(request(UUID.randomUUID())) }
    }

    @Test
    fun `a malformed venue id is a validation error`() {
        val error = assertFailsWith<ValidationException> {
            service.createEvent(CreateEventRequest(venueId = "nope", title = "x"))
        }
        assertEquals("venueId", error.field)
    }

    // ---------------- Time validation ----------------

    @Test
    fun `endTime must be after startTime`() {
        val venueId = givenVenueWithSeats()

        val error = assertFailsWith<ValidationException> {
            service.createEvent(
                request(venueId, startTime = "2026-10-01T22:00:00", endTime = "2026-10-01T19:00:00"),
            )
        }
        assertEquals("endTime", error.field)
    }

    @Test
    fun `an event cannot start and end at the same instant`() {
        val venueId = givenVenueWithSeats()

        assertFailsWith<ValidationException> {
            service.createEvent(
                request(venueId, startTime = "2026-10-01T19:00:00", endTime = "2026-10-01T19:00:00"),
            )
        }
    }

    @Test
    fun `startTime must be in the future`() {
        val venueId = givenVenueWithSeats()

        // "now" is fixed at 2026-09-11T12:00 in this test.
        val error = assertFailsWith<ValidationException> {
            service.createEvent(
                request(venueId, startTime = "2026-09-10T19:00:00", endTime = "2026-09-10T22:00:00"),
            )
        }
        assertEquals("startTime", error.field)
    }

    @Test
    fun `malformed timestamps are rejected with the offending field`() {
        val venueId = givenVenueWithSeats()

        assertEquals(
            "startTime",
            assertFailsWith<ValidationException> {
                service.createEvent(request(venueId, startTime = "01/10/2026 19:00"))
            }.field,
        )
        assertEquals(
            "endTime",
            assertFailsWith<ValidationException> {
                service.createEvent(request(venueId, endTime = "not-a-time"))
            }.field,
        )
    }

    // ---------------- Price validation ----------------

    @Test
    fun `basePrice must be a positive decimal within the column's range`() {
        val venueId = givenVenueWithSeats()

        listOf("0", "-100", "abc", "100000000.00").forEach { bad ->
            assertFailsWith<ValidationException>("should reject basePrice $bad") {
                service.createEvent(request(venueId, basePrice = bad))
            }
        }
    }

    @Test
    fun `basePrice is normalised to two decimal places`() {
        val venueId = givenVenueWithSeats()

        assertEquals("1500.00", service.createEvent(request(venueId, basePrice = "1500")).event.basePrice)
        assertEquals(
            "1500.13",
            service.createEvent(request(venueId, title = "B", basePrice = "1500.125")).event.basePrice,
        )
    }

    @Test
    fun `basePrice is required`() {
        val venueId = givenVenueWithSeats()
        assertFailsWith<ValidationException> {
            service.createEvent(request(venueId).copy(basePrice = null))
        }
    }

    @Test
    fun `title is required`() {
        val venueId = givenVenueWithSeats()
        assertEquals(
            "title",
            assertFailsWith<ValidationException> { service.createEvent(request(venueId, title = "  ")) }.field,
        )
    }

    // ---------------- Updates ----------------

    @Test
    fun `update patches only the supplied fields`() {
        val venueId = givenVenueWithSeats()
        val created = service.createEvent(request(venueId))

        val updated = service.updateEvent(created.event.id, UpdateEventRequest(basePrice = "2000.00"))

        assertEquals("2000.00", updated.basePrice)
        assertEquals("Coldplay Live", updated.title, "untouched fields must be preserved")
        assertEquals("2026-10-01T19:00:00", updated.startTime)
    }

    @Test
    fun `update can publish a draft`() {
        val venueId = givenVenueWithSeats()
        val draft = service.createEvent(request(venueId, status = "DRAFT"))

        assertEquals("PUBLISHED", service.updateEvent(draft.event.id, UpdateEventRequest(status = "PUBLISHED")).status)
    }

    @Test
    fun `a partial time update is validated against the stored value`() {
        val venueId = givenVenueWithSeats()
        val created = service.createEvent(request(venueId))

        // Moving only the end time before the stored start must be rejected.
        val error = assertFailsWith<ValidationException> {
            service.updateEvent(created.event.id, UpdateEventRequest(endTime = "2026-10-01T18:00:00"))
        }
        assertEquals("endTime", error.field)
    }

    @Test
    fun `update does not require the start time to be in the future`() {
        val venueId = givenVenueWithSeats()
        val created = service.createEvent(request(venueId))

        // An admin must be able to fix details of an event that already started.
        val updated = service.updateEvent(
            created.event.id,
            UpdateEventRequest(startTime = "2026-09-01T19:00:00", endTime = "2026-09-01T22:00:00"),
        )
        assertEquals("2026-09-01T19:00:00", updated.startTime)
    }

    @Test
    fun `updating an unknown event is a 404`() {
        assertFailsWith<NotFoundException> {
            service.updateEvent(UUID.randomUUID().toString(), UpdateEventRequest(title = "x"))
        }
    }

    // ---------------- Cancellation ----------------

    @Test
    fun `delete cancels the event instead of removing it`() {
        val venueId = givenVenueWithSeats()
        val created = service.createEvent(request(venueId))
        val eventId = UUID.fromString(created.event.id)

        val cancelled = service.cancelEvent(created.event.id)

        assertEquals("CANCELLED", cancelled.status)
        // The row survives so existing bookings still resolve.
        assertEquals(EventStatus.CANCELLED, events.findById(eventId)!!.status)
        assertEquals(1, events.countAll())
    }

    @Test
    fun `cancelling twice is idempotent`() {
        val venueId = givenVenueWithSeats()
        val created = service.createEvent(request(venueId))

        service.cancelEvent(created.event.id)
        assertEquals("CANCELLED", service.cancelEvent(created.event.id).status)
    }

    @Test
    fun `cancelling an unknown event is a 404`() {
        assertFailsWith<NotFoundException> { service.cancelEvent(UUID.randomUUID().toString()) }
    }

    @Test
    fun `a cancelled event disappears from public listings but stays visible to admins`() {
        val venueId = givenVenueWithSeats()
        val created = service.createEvent(request(venueId))
        service.cancelEvent(created.event.id)

        val publicService = EventService(events, DirectTransactionRunner(), TimeProvider.fixedAt(now))
        assertEquals(0, publicService.listEvents().size)
        assertEquals(1, service.listAllEvents().size)
    }

    // ---------------- Admin listing ----------------

    @Test
    fun `admin listing includes drafts`() {
        val venueId = givenVenueWithSeats()
        service.createEvent(request(venueId, title = "Published Show"))
        service.createEvent(request(venueId, title = "Draft Show", status = "DRAFT"))

        val titles = service.listAllEvents().map { it.title }

        assertEquals(2, titles.size)
        assertTrue(titles.contains("Draft Show"))
    }

    @Test
    fun `admin detail can read a draft`() {
        val venueId = givenVenueWithSeats()
        val draft = service.createEvent(request(venueId, status = "DRAFT"))

        assertEquals("DRAFT", service.getEvent(draft.event.id).status)
    }
}
