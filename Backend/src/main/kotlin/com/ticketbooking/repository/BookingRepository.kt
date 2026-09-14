package com.ticketbooking.repository

import com.ticketbooking.model.Booking
import com.ticketbooking.model.BookingSeatDetail
import com.ticketbooking.model.BookingStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/** Filters for the admin booking list. Null means no constraint. */
data class BookingFilter(
    val eventId: UUID? = null,
    val userId: UUID? = null,
    val status: BookingStatus? = null,
    val limit: Int = 50,
    val offset: Long = 0,
)

/** A booking together with everything needed to render it without extra queries. */
data class BookingDetail(
    val booking: Booking,
    val eventTitle: String,
    val eventStartTime: LocalDateTime,
    val venueName: String,
    val venueCity: String,
    val seats: List<BookingSeatDetail>,
    /** Present on admin listings so bookings can be attributed to a person. */
    val userName: String? = null,
    val userEmail: String? = null,
)

/** A seat to attach to a booking, at the price being charged. */
data class BookingSeatSpec(
    val eventSeatId: UUID,
    val price: BigDecimal,
)

/** Data access for `bookings` and `booking_seats`. */
interface BookingRepository {

    fun findById(id: UUID): Booking?

    /** Booking plus event, venue and seat details. */
    fun findDetailById(id: UUID): BookingDetail?

    /**
     * As [findDetailById], plus the customer's name and email.
     *
     * Separate from [findDetailById] rather than a flag on it: the extra join to
     * `users` exists only for admin views, and the user-facing path must not be able
     * to return another person's contact details by passing the wrong argument.
     */
    fun findDetailByIdWithUser(id: UUID): BookingDetail?

    /** A user's bookings and holds, newest first. */
    fun findDetailsByUser(userId: UUID): List<BookingDetail>

    /** Admin listing across all users. */
    fun search(filter: BookingFilter): List<BookingDetail>

    /** Total matching [filter], ignoring limit/offset, for pagination metadata. */
    fun countMatching(filter: BookingFilter): Long

    /**
     * Inserts a booking and its `booking_seats` rows.
     *
     * Call inside the same transaction that locked the seats. If another
     * transaction has already claimed one of [seats], the partial unique index
     * `uq_booking_seats_active` rejects this insert - the database, not the
     * application, is the final arbiter of double booking.
     */
    fun create(
        id: UUID,
        userId: UUID,
        eventId: UUID,
        bookingReference: String,
        status: BookingStatus,
        totalAmount: BigDecimal,
        createdAt: LocalDateTime,
        holdExpiresAt: OffsetDateTime?,
        seats: List<BookingSeatSpec>,
    ): Booking

    /**
     * Updates status and, when [clearHoldExpiry] is true, nulls `hold_expires_at`
     * (a confirmed or cancelled booking has no hold deadline).
     *
     * @return true if a row changed.
     */
    fun updateStatus(
        id: UUID,
        status: BookingStatus,
        clearHoldExpiry: Boolean = false,
    ): Boolean

    fun updateReference(id: UUID, bookingReference: String): Boolean

    /** True if this booking reference is already taken. */
    fun existsByReference(bookingReference: String): Boolean

    /** Event-seat ids actively claimed by this booking. */
    fun findActiveEventSeatIds(bookingId: UUID): List<UUID>

    /**
     * Bookings holding an ACTIVE claim on any of [eventSeatIds].
     *
     * Needed because `uq_booking_seats_active` permits only one active claim per
     * seat: before a seat can be re-claimed, the previous claim must be released.
     * Without this, re-holding a seat would fail with a raw constraint violation.
     */
    fun findActiveClaimBookingIds(eventSeatIds: List<UUID>): Set<UUID>

    /**
     * Flips `booking_seats.is_active` to false for this booking, releasing its
     * claim so the seats can be sold again while the rows remain as history.
     *
     * @return number of rows deactivated.
     */
    fun deactivateSeats(bookingId: UUID): Int

    /**
     * Sweeper support: PENDING bookings whose `hold_expires_at` is at or before
     * [now], oldest first.
     *
     * @param limit caps the batch so one sweep cannot monopolise the pool.
     */
    fun findExpiredHolds(now: OffsetDateTime, limit: Int = 200): List<Booking>

    fun countAll(): Long
}
