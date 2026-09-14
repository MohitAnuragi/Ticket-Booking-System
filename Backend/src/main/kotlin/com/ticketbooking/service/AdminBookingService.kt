package com.ticketbooking.service

import com.ticketbooking.dto.BookingListResponse
import com.ticketbooking.dto.BookingResponse
import com.ticketbooking.dto.toResponse
import com.ticketbooking.model.BookingStatus
import com.ticketbooking.repository.BookingFilter
import com.ticketbooking.repository.BookingRepository
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.ValidationException
import com.ticketbooking.util.Validators
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Admin oversight of bookings (spec section 6.4).
 *
 * Separate from [BookingService] on purpose: that class is about acting on YOUR OWN
 * bookings and every method there starts with an ownership check. Here there is no
 * ownership check by design, so keeping the two apart means the authorisation model
 * of each is obvious from which class a method lives in - an admin-only rule can
 * never leak into the user-facing path by accident.
 *
 * Listing is paginated because an admin listing is unbounded by nature: one popular
 * event can produce thousands of bookings, and a response that grows without limit
 * is a denial-of-service waiting to happen.
 */
class AdminBookingService(
    private val bookings: BookingRepository,
    private val seats: SeatRepository,
    private val transactions: TransactionRunner,
) {

    private val log = LoggerFactory.getLogger(AdminBookingService::class.java)

    /**
     * Every booking, newest first, optionally filtered.
     *
     * Filters combine with AND, and an absent filter means no constraint. `total` is
     * the count of ALL matching rows, not the size of this page, so the client can
     * render "showing 1-50 of 812" and page correctly.
     *
     * @throws ValidationException on an unparseable filter or an out-of-range page.
     */
    fun listBookings(
        rawEventId: String? = null,
        rawUserId: String? = null,
        rawStatus: String? = null,
        rawLimit: String? = null,
        rawOffset: String? = null,
    ): BookingListResponse {
        val filter = BookingFilter(
            eventId = rawEventId?.takeIf { it.isNotBlank() }?.let { Validators.parseUuid(it, "eventId") },
            userId = rawUserId?.takeIf { it.isNotBlank() }?.let { Validators.parseUuid(it, "userId") },
            status = parseStatus(rawStatus),
            limit = parseLimit(rawLimit),
            offset = parseOffset(rawOffset),
        )

        return transactions.inTransaction {
            val page = bookings.search(filter)
            // Counted inside the same transaction as the page, so the total cannot
            // disagree with the rows the admin is looking at.
            val total = bookings.countMatching(filter)

            BookingListResponse(
                // includeUser: an admin listing is useless without knowing who booked.
                bookings = page.map { it.toResponse(includeUser = true) },
                total = total,
                limit = filter.limit,
                offset = filter.offset,
            )
        }
    }

    /**
     * Any booking, regardless of owner, with the customer's details attached.
     *
     * @throws NotFoundException if it does not exist.
     */
    fun getBooking(rawBookingId: String?): BookingResponse {
        val bookingId = Validators.parseUuid(rawBookingId, "id")

        val detail = transactions.inTransaction { bookings.findDetailByIdWithUser(bookingId) }
            ?: throw NotFoundException("Booking not found")

        return detail.toResponse(includeUser = true)
    }

    /**
     * Cancels any user's booking - the support case: a customer phones in, or an
     * event is rescheduled and its bookings have to be released.
     *
     * Differs from [BookingService.cancelBooking] in exactly two ways, both
     * deliberate:
     *  - no ownership check, since the admin is acting on someone else's booking;
     *  - no start-time window, because releasing seats for an event that already
     *    started is a legitimate correction for an admin even though it is not
     *    something a customer may do to get a refund.
     *
     * The acting admin's id is logged: an admin cancelling a stranger's ticket must
     * be attributable afterwards.
     *
     * @throws NotFoundException if the booking does not exist.
     * @throws ConflictException if it is already in a terminal state.
     */
    fun cancelBooking(actingAdminId: UUID, rawBookingId: String?): BookingResponse {
        val bookingId = Validators.parseUuid(rawBookingId, "id")

        transactions.inTransaction {
            val booking = bookings.findById(bookingId)
                ?: throw NotFoundException("Booking not found")

            when (booking.status) {
                // Idempotent, so a retried request is harmless.
                BookingStatus.CANCELLED -> return@inTransaction

                BookingStatus.EXPIRED -> throw ConflictException(
                    errorCode = "HOLD_EXPIRED",
                    message = "This hold already expired and its seats were released; " +
                        "there is nothing to cancel",
                )

                BookingStatus.PENDING, BookingStatus.CONFIRMED -> Unit
            }

            // Mirrors the release sequence in BookingService.cancelBooking: free the
            // seats and close the booking in one transaction, so a seat is never
            // released without the booking recording why.
            val claimedSeatIds = bookings.findActiveEventSeatIds(bookingId)
            bookings.deactivateSeats(bookingId)
            bookings.updateStatus(bookingId, BookingStatus.CANCELLED, clearHoldExpiry = true)
            if (claimedSeatIds.isNotEmpty()) {
                seats.markAvailable(claimedSeatIds)
            }

            log.warn(
                "ADMIN $actingAdminId cancelled ${booking.status} booking $bookingId " +
                    "belonging to user ${booking.userId}; released ${claimedSeatIds.size} seat(s)",
            )
        }

        return getBooking(bookingId.toString())
    }

    // ---------------- filter parsing ----------------

    private fun parseStatus(raw: String?): BookingStatus? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return BookingStatus.parseOrNull(value)
            ?: throw ValidationException(
                "status must be one of ${BookingStatus.entries.joinToString(", ") { it.name }}",
                "status",
            )
    }

    /**
     * Rejects an unparseable or oversized limit rather than silently clamping it: an
     * admin who asks for 5000 rows and gets 200 without being told would conclude
     * there are only 200 bookings.
     */
    private fun parseLimit(raw: String?): Int {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_LIMIT
        val limit = value.toIntOrNull()
            ?: throw ValidationException("limit must be a whole number", "limit")
        return Validators.requireInRange(limit, "limit", min = 1, max = MAX_LIMIT)
    }

    private fun parseOffset(raw: String?): Long {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        val offset = value.toLongOrNull()
            ?: throw ValidationException("offset must be a whole number", "offset")
        if (offset < 0) throw ValidationException("offset cannot be negative", "offset")
        return offset
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
