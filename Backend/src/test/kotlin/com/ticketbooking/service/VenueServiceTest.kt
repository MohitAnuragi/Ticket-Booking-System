package com.ticketbooking.service

import com.ticketbooking.dto.CreateVenueRequest
import com.ticketbooking.dto.SeatLayoutRequest
import com.ticketbooking.model.SeatType
import com.ticketbooking.model.Venue
import com.ticketbooking.repository.DirectTransactionRunner
import com.ticketbooking.repository.FakeSeatRepository
import com.ticketbooking.repository.FakeVenueRepository
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.ValidationException
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [VenueService]: venue creation and bulk seat layout expansion.
 */
class VenueServiceTest {

    private val venues = FakeVenueRepository()
    private val seats = FakeSeatRepository()
    private val service = VenueService(
        venues = venues,
        seats = seats,
        transactions = DirectTransactionRunner(),
    )

    private fun givenVenue(capacity: Int = 0): UUID {
        val id = UUID.randomUUID()
        venues.put(Venue(id, "NSCI Dome", "1 Test Road", "Mumbai", capacity))
        return id
    }

    private fun layout(
        rows: List<String>? = listOf("A", "B", "C"),
        seatsPerRow: Int? = 10,
        seatType: String? = null,
        priceMultiplier: String? = null,
    ) = SeatLayoutRequest(rows, seatsPerRow, seatType, priceMultiplier)

    // ---------------- Venue creation ----------------

    @Test
    fun `createVenue stores a trimmed venue`() {
        val response = service.createVenue(
            CreateVenueRequest(name = "  NSCI Dome ", address = " 1 Road ", city = " Mumbai ", totalCapacity = 500),
        )

        assertEquals("NSCI Dome", response.name)
        assertEquals("Mumbai", response.city)
        assertEquals("1 Road", response.address)
        assertEquals(500, response.totalCapacity)
        assertEquals(0, response.seatCount, "no seats exist until a layout is generated")
    }

    @Test
    fun `createVenue defaults capacity to zero so it can be derived from the layout`() {
        val response = service.createVenue(CreateVenueRequest(name = "Arena", city = "Delhi"))
        assertEquals(0, response.totalCapacity)
    }

    @Test
    fun `createVenue requires name and city`() {
        assertEquals(
            "name",
            assertFailsWith<ValidationException> {
                service.createVenue(CreateVenueRequest(name = " ", city = "Mumbai"))
            }.field,
        )
        assertEquals(
            "city",
            assertFailsWith<ValidationException> {
                service.createVenue(CreateVenueRequest(name = "Arena", city = null))
            }.field,
        )
    }

    @Test
    fun `createVenue rejects a negative capacity`() {
        assertFailsWith<ValidationException> {
            service.createVenue(CreateVenueRequest(name = "Arena", city = "Delhi", totalCapacity = -1))
        }
    }

    // ---------------- Layout expansion ----------------

    @Test
    fun `layout expands rows times seatsPerRow`() {
        val venueId = givenVenue()

        val result = service.generateSeatLayout(venueId.toString(), layout(listOf("A", "B", "C"), 10))

        assertEquals(30, result.seatsCreated)
        assertEquals(30L, result.totalSeats)
        assertEquals(30, seats.findSeatsByVenue(venueId).size)
    }

    @Test
    fun `layout numbers seats from one and labels them row-then-number`() {
        val venueId = givenVenue()

        service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 3))

        assertEquals(listOf("A1", "A2", "A3"), seats.findSeatsByVenue(venueId).map { it.label })
    }

    @Test
    fun `layout updates the venue capacity to the real seat count`() {
        val venueId = givenVenue(capacity = 0)

        service.generateSeatLayout(venueId.toString(), layout(listOf("A", "B"), 5))

        assertEquals(10, venues.findById(venueId)!!.totalCapacity)
    }

    @Test
    fun `layout can be called repeatedly to build tiers`() {
        val venueId = givenVenue()

        service.generateSeatLayout(venueId.toString(), layout(listOf("A", "B"), 5, "PREMIUM"))
        val second = service.generateSeatLayout(venueId.toString(), layout(listOf("C", "D"), 5, "REGULAR"))

        assertEquals(10, second.seatsCreated)
        assertEquals(20L, second.totalSeats)

        val byType = seats.findSeatsByVenue(venueId).groupBy { it.seatType }
        assertEquals(10, byType.getValue(SeatType.PREMIUM).size)
        assertEquals(10, byType.getValue(SeatType.REGULAR).size)
    }

    @Test
    fun `row labels are upper-cased`() {
        val venueId = givenVenue()

        service.generateSeatLayout(venueId.toString(), layout(listOf("a", "b"), 2))

        assertTrue(seats.findSeatsByVenue(venueId).all { it.seatRow in setOf("A", "B") })
    }

    // ---------------- Multipliers ----------------

    @Test
    fun `multiplier defaults per tier`() {
        val venueId = givenVenue()

        service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 1, "REGULAR"))
        service.generateSeatLayout(venueId.toString(), layout(listOf("B"), 1, "PREMIUM"))
        service.generateSeatLayout(venueId.toString(), layout(listOf("C"), 1, "VIP"))

        val byRow = seats.findSeatsByVenue(venueId).associateBy { it.seatRow }

        assertEquals(BigDecimal("1.00"), byRow.getValue("A").priceMultiplier)
        assertEquals(BigDecimal("1.50"), byRow.getValue("B").priceMultiplier)
        assertEquals(BigDecimal("2.50"), byRow.getValue("C").priceMultiplier)
    }

    @Test
    fun `an explicit multiplier overrides the tier default`() {
        val venueId = givenVenue()

        service.generateSeatLayout(
            venueId.toString(),
            layout(listOf("A"), 1, "REGULAR", priceMultiplier = "3.25"),
        )

        assertEquals(BigDecimal("3.25"), seats.findSeatsByVenue(venueId).single().priceMultiplier)
    }

    @Test
    fun `invalid multipliers are rejected`() {
        val venueId = givenVenue()

        listOf("0", "-1", "abc", "100.00").forEach { bad ->
            assertFailsWith<ValidationException>("should reject multiplier $bad") {
                service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 1, null, bad))
            }
        }
    }

    // ---------------- Validation ----------------

    @Test
    fun `empty or missing rows is a validation error`() {
        val venueId = givenVenue()

        assertEquals(
            "rows",
            assertFailsWith<ValidationException> {
                service.generateSeatLayout(venueId.toString(), layout(rows = emptyList()))
            }.field,
        )
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(rows = null))
        }
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(rows = listOf("  ")))
        }
    }

    @Test
    fun `duplicate row labels are rejected before touching the database`() {
        val venueId = givenVenue()

        // "a" and "A" normalise to the same row and would collide on insert.
        val error = assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A", "a"), 2))
        }
        assertEquals("rows", error.field)
        assertEquals(0, seats.findSeatsByVenue(venueId).size)
    }

    @Test
    fun `overlong or non-alphanumeric row labels are rejected`() {
        val venueId = givenVenue()

        // seat_row is VARCHAR(5).
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("TOOLONG"), 2))
        }
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A-1"), 2))
        }
    }

    @Test
    fun `seatsPerRow must be a sensible positive number`() {
        val venueId = givenVenue()

        assertEquals(
            "seatsPerRow",
            assertFailsWith<ValidationException> {
                service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 0))
            }.field,
        )
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A"), -5))
        }
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A"), null))
        }
        assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 100_000))
        }
    }

    @Test
    fun `an unknown seat type is rejected`() {
        val venueId = givenVenue()

        val error = assertFailsWith<ValidationException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 2, "GOLD"))
        }
        assertEquals("seatType", error.field)
    }

    @Test
    fun `a layout for an unknown venue is a 404`() {
        assertFailsWith<NotFoundException> {
            service.generateSeatLayout(UUID.randomUUID().toString(), layout())
        }
    }

    @Test
    fun `a malformed venue id is a validation error`() {
        assertEquals(
            "id",
            assertFailsWith<ValidationException> { service.generateSeatLayout("nope", layout()) }.field,
        )
    }

    // ---------------- Duplicate protection ----------------

    @Test
    fun `re-generating the same rows is rejected whole rather than partially applied`() {
        val venueId = givenVenue()
        service.generateSeatLayout(venueId.toString(), layout(listOf("A", "B"), 5))

        // Overlaps on row A, so the entire request must fail - a partial layout
        // would leave the admin guessing what was created.
        val error = assertFailsWith<ConflictException> {
            service.generateSeatLayout(venueId.toString(), layout(listOf("A", "Z"), 5))
        }

        assertEquals("SEATS_ALREADY_EXIST", error.errorCode)
        assertTrue(error.message.contains("A1"), "the clashing labels should be named: ${error.message}")
        assertEquals(10, seats.findSeatsByVenue(venueId).size, "no new seats should have been added")
    }

    // ---------------- Listing ----------------

    @Test
    fun `listVenues reports the real seat count`() {
        val venueId = givenVenue(capacity = 999)
        service.generateSeatLayout(venueId.toString(), layout(listOf("A"), 4))

        val listed = service.listVenues().single()

        assertEquals(4L, listed.seatCount)
        // Capacity was corrected to match the generated layout.
        assertEquals(4, listed.totalCapacity)
    }

    @Test
    fun `getVenue 404s for an unknown id`() {
        assertFailsWith<NotFoundException> { service.getVenue(UUID.randomUUID().toString()) }
    }
}
