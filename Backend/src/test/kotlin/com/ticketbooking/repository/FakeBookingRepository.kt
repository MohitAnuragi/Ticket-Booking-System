package com.ticketbooking.repository

import com.ticketbooking.model.Booking
import com.ticketbooking.model.BookingSeat
import com.ticketbooking.model.BookingSeatDetail
import com.ticketbooking.model.BookingStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * In-memory [BookingRepository] for unit tests.
 *
 * Mirrors the production behaviour that matters to the service layer, including the
 * partial unique index `uq_booking_seats_active`: [create] refuses a seat that
 * already has an ACTIVE claim, exactly as PostgreSQL would. That is what lets the
 * cancel-then-rebook path be tested honestly without a database.
 */
class FakeBookingRepository(
    /** Supplies event/venue details for [findDetailById], mirroring the real join. */
    var eventResolver: ((UUID) -> EventWithVenue?)? = null,
    /** Supplies seat position details, mirroring the join to seats. */
    var seatResolver: ((UUID) -> Triple<String, Int, com.ticketbooking.model.SeatType>?)? = null,
    /** Supplies user details for admin listings. */
    var userResolver: ((UUID) -> Pair<String, String>?)? = null,
) : BookingRepository {

    private val store = linkedMapOf<UUID, Booking>()
    private val seatRows = mutableListOf<BookingSeat>()

    override fun findById(id: UUID): Booking? = store[id]

    override fun findDetailById(id: UUID): BookingDetail? =
        store[id]?.let { toDetail(it, includeUser = false) }

    override fun findDetailByIdWithUser(id: UUID): BookingDetail? =
        store[id]?.let { toDetail(it, includeUser = true) }

    override fun findDetailsByUser(userId: UUID): List<BookingDetail> =
        store.values
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .map { toDetail(it, includeUser = false) }

    override fun search(filter: BookingFilter): List<BookingDetail> =
        matching(filter)
            .sortedByDescending { it.createdAt }
            .drop(filter.offset.toInt())
            .take(filter.limit)
            .map { toDetail(it, includeUser = true) }

    override fun countMatching(filter: BookingFilter): Long = matching(filter).size.toLong()

    private fun matching(filter: BookingFilter): List<Booking> = store.values.filter { booking ->
        (filter.eventId == null || booking.eventId == filter.eventId) &&
            (filter.userId == null || booking.userId == filter.userId) &&
            (filter.status == null || booking.status == filter.status)
    }

    override fun create(
        id: UUID,
        userId: UUID,
        eventId: UUID,
        bookingReference: String,
        status: BookingStatus,
        totalAmount: BigDecimal,
        createdAt: LocalDateTime,
        holdExpiresAt: OffsetDateTime?,
        seats: List<BookingSeatSpec>,
    ): Booking {
        // Mirrors UNIQUE(booking_reference).
        require(store.values.none { it.bookingReference == bookingReference }) {
            "duplicate booking reference $bookingReference"
        }

        // Mirrors the partial unique index uq_booking_seats_active: at most one
        // ACTIVE claim per event seat.
        seats.forEach { spec ->
            require(seatRows.none { it.eventSeatId == spec.eventSeatId && it.isActive }) {
                "event seat ${spec.eventSeatId} already has an active claim"
            }
        }

        val booking = Booking(
            id = id,
            userId = userId,
            eventId = eventId,
            bookingReference = bookingReference,
            status = status,
            totalAmount = totalAmount,
            createdAt = createdAt,
            holdExpiresAt = holdExpiresAt,
        )
        store[id] = booking
        seats.forEach { spec ->
            seatRows += BookingSeat(
                id = UUID.randomUUID(),
                bookingId = id,
                eventSeatId = spec.eventSeatId,
                price = spec.price,
                isActive = true,
            )
        }
        return booking
    }

    override fun updateStatus(id: UUID, status: BookingStatus, clearHoldExpiry: Boolean): Boolean {
        val existing = store[id] ?: return false
        store[id] = existing.copy(
            status = status,
            holdExpiresAt = if (clearHoldExpiry) null else existing.holdExpiresAt,
        )
        return true
    }

    override fun updateReference(id: UUID, bookingReference: String): Boolean {
        val existing = store[id] ?: return false
        store[id] = existing.copy(bookingReference = bookingReference)
        return true
    }

    override fun existsByReference(bookingReference: String): Boolean =
        store.values.any { it.bookingReference == bookingReference }

    override fun findActiveEventSeatIds(bookingId: UUID): List<UUID> =
        seatRows.filter { it.bookingId == bookingId && it.isActive }.map { it.eventSeatId }

    override fun findActiveClaimBookingIds(eventSeatIds: List<UUID>): Set<UUID> =
        seatRows
            .filter { it.isActive && it.eventSeatId in eventSeatIds }
            .mapTo(mutableSetOf()) { it.bookingId }

    override fun deactivateSeats(bookingId: UUID): Int {
        var changed = 0
        seatRows.replaceAll { row ->
            if (row.bookingId == bookingId && row.isActive) {
                changed++
                row.copy(isActive = false)
            } else {
                row
            }
        }
        return changed
    }

    override fun findExpiredHolds(now: OffsetDateTime, limit: Int): List<Booking> =
        store.values
            .filter { it.isHoldExpiredAt(now) }
            .sortedBy { it.createdAt }
            .take(limit)

    override fun countAll(): Long = store.size.toLong()

    // ---- test helpers ----

    fun all(): List<Booking> = store.values.toList()

    fun seatRowsFor(bookingId: UUID): List<BookingSeat> = seatRows.filter { it.bookingId == bookingId }

    private fun toDetail(booking: Booking, includeUser: Boolean): BookingDetail {
        val event = eventResolver?.invoke(booking.eventId)
        val user = if (includeUser) userResolver?.invoke(booking.userId) else null

        val seats = seatRows
            .filter { it.bookingId == booking.id }
            .map { row ->
                val position = seatResolver?.invoke(row.eventSeatId)
                BookingSeatDetail(
                    eventSeatId = row.eventSeatId,
                    seatRow = position?.first ?: "A",
                    seatNumber = position?.second ?: 0,
                    seatType = position?.third ?: com.ticketbooking.model.SeatType.REGULAR,
                    price = row.price,
                )
            }
            .sortedWith(compareBy({ it.seatRow }, { it.seatNumber }))

        return BookingDetail(
            booking = booking,
            eventTitle = event?.event?.title ?: "Test Event",
            eventStartTime = event?.event?.startTime ?: LocalDateTime.of(2026, 10, 1, 19, 0),
            venueName = event?.venueName ?: "Test Venue",
            venueCity = event?.venueCity ?: "Testville",
            seats = seats,
            userName = user?.first,
            userEmail = user?.second,
        )
    }
}
