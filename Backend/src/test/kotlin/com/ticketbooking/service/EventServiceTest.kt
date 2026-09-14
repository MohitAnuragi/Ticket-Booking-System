package com.ticketbooking.service

import com.ticketbooking.model.Event
import com.ticketbooking.model.EventStatus
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.EventWithVenue
import com.ticketbooking.repository.FakeEventRepository
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
import kotlin.test.assertTrue

/**
 * [EventService] filtering and visibility rules, against an in-memory repository.
 *
 * The most important behaviour asserted here is that DRAFT events are invisible to
 * public callers - both in listings and by direct id.
 */
class EventServiceTest {

    private val now = OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC)

    private val events = FakeEventRepository()
    private val service = EventService(
        events = events,
        transactions = DirectTransactionRunner(),
        time = TimeProvider.fixedAt(now),
    )

    private fun addEvent(
        title: String,
        city: String = "Mumbai",
        category: String? = "Concert",
        status: EventStatus = EventStatus.PUBLISHED,
        start: LocalDateTime = LocalDateTime.of(2026, 10, 1, 19, 0),
        description: String? = null,
        venueName: String = "NSCI Dome",
    ): UUID {
        val id = UUID.randomUUID()
        events.put(
            EventWithVenue(
                event = Event(
                    id = id,
                    venueId = UUID.randomUUID(),
                    title = title,
                    description = description,
                    category = category,
                    startTime = start,
                    endTime = start.plusHours(3),
                    basePrice = BigDecimal("1500.00"),
                    status = status,
                    posterUrl = null,
                ),
                venueName = venueName,
                venueCity = city,
                venueAddress = "1 Test Road",
            ),
        )
        return id
    }

    // ---------------- Visibility ----------------

    @Test
    fun `listing shows only published events`() {
        addEvent("Published Show")
        addEvent("Draft Show", status = EventStatus.DRAFT)
        addEvent("Cancelled Show", status = EventStatus.CANCELLED)

        val listed = service.listEvents()

        assertEquals(1, listed.size)
        assertEquals("Published Show", listed.single().title)
    }

    @Test
    fun `admin listing can include drafts`() {
        addEvent("Published Show")
        addEvent("Draft Show", status = EventStatus.DRAFT)

        val listed = service.listEvents(includeUnpublished = true)

        assertEquals(2, listed.size)
    }

    @Test
    fun `fetching a draft by id returns 404 for the public`() {
        val draftId = addEvent("Draft Show", status = EventStatus.DRAFT)

        // 404 rather than 403: confirming the id exists would itself leak information.
        assertFailsWith<NotFoundException> { service.getEvent(draftId.toString()) }

        // The same id is reachable for an admin caller.
        assertEquals("Draft Show", service.getEvent(draftId, includeUnpublished = true).title)
    }

    @Test
    fun `fetching an unknown id returns 404`() {
        assertFailsWith<NotFoundException> { service.getEvent(UUID.randomUUID().toString()) }
    }

    @Test
    fun `fetching a malformed id returns a validation error`() {
        val error = assertFailsWith<ValidationException> { service.getEvent("not-a-uuid") }
        assertEquals("id", error.field)
    }

    @Test
    fun `event detail includes venue information`() {
        val id = addEvent("Coldplay Live", city = "Mumbai", venueName = "NSCI Dome")

        val detail = service.getEvent(id.toString())

        assertEquals("Coldplay Live", detail.title)
        assertEquals("NSCI Dome", detail.venue.name)
        assertEquals("Mumbai", detail.venue.city)
        assertEquals("1500.00", detail.basePrice)
        assertEquals("2026-10-01T19:00:00", detail.startTime)
    }

    // ---------------- Filters ----------------

    @Test
    fun `search matches title and description case insensitively`() {
        addEvent("Coldplay Live")
        addEvent("Jazz Night", description = "Featuring a coldplay tribute set")
        addEvent("Comedy Hour")

        assertEquals(2, service.listEvents(search = "COLDPLAY").size)
        assertEquals(1, service.listEvents(search = "comedy").size)
        assertEquals(0, service.listEvents(search = "opera").size)
    }

    @Test
    fun `city filter is case insensitive and exact`() {
        addEvent("Mumbai Show", city = "Mumbai")
        addEvent("Delhi Show", city = "Delhi")

        assertEquals("Mumbai Show", service.listEvents(city = "mumbai").single().title)
        assertEquals(0, service.listEvents(city = "Mum").size, "city must not match partially")
    }

    @Test
    fun `category filter is case insensitive`() {
        addEvent("Concert Show", category = "Concert")
        addEvent("Theatre Show", category = "Theatre")

        assertEquals("Concert Show", service.listEvents(category = "concert").single().title)
    }

    @Test
    fun `date filter matches the whole calendar day`() {
        addEvent("Early Slot", start = LocalDateTime.of(2026, 10, 1, 0, 0))
        addEvent("Late Slot", start = LocalDateTime.of(2026, 10, 1, 23, 59))
        addEvent("Next Day", start = LocalDateTime.of(2026, 10, 2, 0, 0))

        val onOct1 = service.listEvents(date = "2026-10-01")

        assertEquals(2, onOct1.size, "both slots on the day must match, boundaries included")
        assertTrue(onOct1.none { it.title == "Next Day" })
    }

    @Test
    fun `a malformed date is a validation error not a silent full listing`() {
        addEvent("Any Show")

        val error = assertFailsWith<ValidationException> { service.listEvents(date = "01-10-2026") }
        assertEquals("date", error.field)
        assertFailsWith<ValidationException> { service.listEvents(date = "tomorrow") }
    }

    @Test
    fun `blank filters are treated as absent`() {
        addEvent("Only Show")

        // An empty search form must not filter everything out.
        val listed = service.listEvents(search = "  ", city = "", category = "  ", date = "")
        assertEquals(1, listed.size)
    }

    @Test
    fun `upcomingOnly hides events that already started`() {
        events.now = now.toLocalDateTime()
        addEvent("Past Show", start = now.minusDays(2).toLocalDateTime())
        addEvent("Future Show", start = now.plusDays(2).toLocalDateTime())

        assertEquals(2, service.listEvents().size, "by default past events are still listed")
        assertEquals("Future Show", service.listEvents(upcomingOnly = true).single().title)
    }

    @Test
    fun `filters combine as AND`() {
        addEvent("Coldplay Mumbai", city = "Mumbai", category = "Concert")
        addEvent("Coldplay Delhi", city = "Delhi", category = "Concert")
        addEvent("Comedy Mumbai", city = "Mumbai", category = "Comedy")

        val result = service.listEvents(search = "coldplay", city = "Mumbai", category = "Concert")

        assertEquals("Coldplay Mumbai", result.single().title)
    }

    @Test
    fun `results are ordered by start time`() {
        addEvent("Third", start = LocalDateTime.of(2026, 12, 1, 19, 0))
        addEvent("First", start = LocalDateTime.of(2026, 10, 1, 19, 0))
        addEvent("Second", start = LocalDateTime.of(2026, 11, 1, 19, 0))

        assertEquals(listOf("First", "Second", "Third"), service.listEvents().map { it.title })
    }

    // ---------------- Filter options ----------------

    @Test
    fun `filter options expose distinct sorted values`() {
        addEvent("A", city = "Mumbai", category = "Concert")
        addEvent("B", city = "Delhi", category = "Comedy")
        addEvent("C", city = "Mumbai", category = "Concert")
        addEvent("D", city = "Bengaluru", category = null)

        val options = service.listFilterOptions()

        assertEquals(listOf("Bengaluru", "Delhi", "Mumbai"), options.cities)
        assertEquals(listOf("Comedy", "Concert"), options.categories)
    }
}
