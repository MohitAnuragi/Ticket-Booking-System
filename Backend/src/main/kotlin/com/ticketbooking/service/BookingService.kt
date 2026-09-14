package com.ticketbooking.service

import com.ticketbooking.dto.BookingResponse
import com.ticketbooking.dto.HoldRequest
import com.ticketbooking.dto.toResponse
import com.ticketbooking.model.BookingStatus
import com.ticketbooking.model.EventStatus
import com.ticketbooking.repository.BookingRepository
import com.ticketbooking.repository.BookingSeatSpec
import com.ticketbooking.repository.EventRepository
import com.ticketbooking.repository.SeatRepository
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.ForbiddenException
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.SeatsUnavailableException
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.ValidationException
import com.ticketbooking.util.Validators
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.UUID

/** Tunables for the booking flow, read from `app.hold.*`. */
data class BookingConfig(
    /** How long seats stay held before returning to the market. Agreed: 24 hours. */
    val holdTtlHours: Long = 24,
    /** Upper bound on seats in one booking, to stop a single user taking a whole venue. */
    val maxSeatsPerBooking: Int = 10,
)

/**
 * What one pass of the expiry sweeper did.
 *
 * Returned rather than only logged so the sweeper can report it, and so the
 * behaviour is assertable in tests without reading log output.
 */
data class SweepResult(
    /** PENDING holds moved to EXPIRED. */
    val holdsExpired: Int = 0,
    /** Seats returned to AVAILABLE by expiring those holds. */
    val seatsReleased: Int = 0,
    /**
     * Seats found LOCKED past their deadline with no live booking to release them.
     * Normally zero; a non-zero count means a hold and its seats drifted apart and
     * is worth investigating.
     */
    val orphanLocksReleased: Int = 0,
) {
    val didNothing: Boolean
        get() = holdsExpired == 0 && seatsReleased == 0 && orphanLocksReleased == 0

    override fun toString(): String =
        "$holdsExpired hold(s) expired, $seatsReleased seat(s) released" +
            if (orphanLocksReleased > 0) ", $orphanLocksReleased orphaned lock(s) cleared" else ""
}

/**
 * Seat holding, confirmation and cancellation.
 *
 * DOUBLE-BOOKING PREVENTION - three layers, in order of authority:
 *
 * 1. `SELECT ... FOR UPDATE` (via [SeatRepository.lockEventSeatsForUpdate]) takes a
 *    row lock on each requested seat. A second transaction wanting the same seat
 *    BLOCKS until this one finishes, so the availability check below cannot go stale
 *    between reading and writing.
 * 2. The status check inside that lock rejects seats already held or sold, returning
 *    409 with the exact offending ids.
 * 3. The partial unique index `uq_booking_seats_active` is the final arbiter: even if
 *    the application logic were wrong, PostgreSQL refuses a second active claim on
 *    the same seat and the transaction rolls back.
 *
 * Layer 3 is what makes the guarantee real - 1 and 2 exist to turn a raw constraint
 * violation into a clean, actionable API response.
 */
class BookingService(
    private val bookings: BookingRepository,
    private val seats: SeatRepository,
    private val events: EventRepository,
    private val transactions: TransactionRunner,
    private val config: BookingConfig = BookingConfig(),
    private val time: TimeProvider = TimeProvider.SYSTEM,
) {

    private val log = LoggerFactory.getLogger(BookingService::class.java)
    private val random = SecureRandom()

    /**
     * Holds seats for [config].holdTtlHours and creates the matching PENDING booking.
     *
     * Everything happens in ONE transaction: lock, verify, mark LOCKED, insert the
     * booking and its seats. A failure at any point leaves no trace, so seats are
     * never stranded in LOCKED with no booking to release them.
     *
     * @throws ValidationException on an empty, oversized or duplicate-laden request.
     * @throws NotFoundException if the event or any requested seat does not exist.
     * @throws ConflictException if the event is not open for booking.
     * @throws SeatsUnavailableException (409) listing seats that are already taken.
     */
    fun holdSeats(userId: UUID, rawEventId: String?, request: HoldRequest): BookingResponse {
        val eventId = Validators.parseUuid(rawEventId, "id")
        val requestedIds = parseSeatIds(request.eventSeatIds)
        val now = time.nowOffset()
        val expiresAt = time.offsetPlusHours(config.holdTtlHours)

        val bookingId = transactions.inTransaction {
            val event = events.findById(eventId) ?: throw NotFoundException("Event not found")

            // Refuse before locking anything: no point holding seats for an event
            // nobody can attend.
            if (event.status != EventStatus.PUBLISHED) {
                throw ConflictException(
                    errorCode = "EVENT_NOT_BOOKABLE",
                    message = when (event.status) {
                        EventStatus.CANCELLED -> "This event has been cancelled"
                        else -> "This event is not open for booking yet"
                    },
                )
            }
            if (event.hasStartedAt(time.nowUtc())) {
                throw ConflictException(
                    errorCode = "EVENT_ALREADY_STARTED",
                    message = "This event has already started",
                )
            }

            // THE critical read: takes a row lock on every requested seat, so no other
            // transaction can change their status until this one commits.
            val locked = seats.lockEventSeatsForUpdate(eventId, requestedIds)

            val foundIds = locked.map { it.eventSeat.id }.toSet()
            val missing = requestedIds.filterNot { it in foundIds }
            if (missing.isNotEmpty()) {
                throw NotFoundException(
                    "These seats do not belong to this event: ${missing.joinToString(", ")}",
                )
            }

            // A seat the caller already holds is not a conflict - re-submitting the
            // same selection should be harmless.
            val unavailable = locked.filterNot { it.eventSeat.isClaimableBy(userId, now) }
            if (unavailable.isNotEmpty()) {
                val labels = unavailable.map { it.seat.label }
                log.info("Hold rejected for user $userId on event $eventId: ${labels.joinToString(", ")} taken")
                throw SeatsUnavailableException(
                    unavailableSeatIds = unavailable.map { it.eventSeat.id.toString() },
                    message = "These seats are no longer available: ${labels.joinToString(", ")}",
                )
            }

            // Priced through the same helper the seat map uses, so what the user was
            // shown and what they are charged cannot drift apart.
            val pricedSeats = locked.map { row ->
                BookingSeatSpec(
                    eventSeatId = row.eventSeat.id,
                    price = SeatService.priceFor(event.basePrice, row.seat.priceMultiplier),
                )
            }
            val total = pricedSeats.fold(BigDecimal.ZERO) { sum, seat -> sum.add(seat.price) }

            // Release any stale claim on these seats before re-claiming them.
            releaseSupersededClaims(requestedIds, userId)

            seats.markLocked(requestedIds, userId, expiresAt)

            val newBookingId = UUID.randomUUID()
            bookings.create(
                id = newBookingId,
                userId = userId,
                eventId = eventId,
                bookingReference = generateBookingReference(),
                status = BookingStatus.PENDING,
                totalAmount = total,
                createdAt = time.nowUtc(),
                holdExpiresAt = expiresAt,
                seats = pricedSeats,
            )

            log.info(
                "User $userId holding ${pricedSeats.size} seat(s) for event $eventId " +
                    "as booking $newBookingId until $expiresAt",
            )
            newBookingId
        }

        return loadDetail(bookingId)
    }

    /**
     * Turns a PENDING hold into a CONFIRMED booking.
     *
     * Re-locks the held seats and re-checks them before committing. That second
     * check is not paranoia: between the hold and this call the hold may have
     * expired and another user may legitimately have taken the seats, in which case
     * confirming would sell the same seat twice.
     *
     * Idempotent - confirming an already-CONFIRMED booking returns it unchanged
     * rather than erroring, so a double-clicked button or a retried request is
     * harmless.
     *
     * The booking reference is NOT regenerated; the code issued at hold time stays
     * with the booking for its whole life.
     *
     * @throws NotFoundException if no such booking exists.
     * @throws ForbiddenException if the booking belongs to another user.
     * @throws ConflictException if the hold has expired or the booking is already
     *         cancelled or expired.
     * @throws SeatsUnavailableException if a seat was taken while the hold lapsed.
     */
    fun confirmBooking(userId: UUID, rawBookingId: String?): BookingResponse {
        val bookingId = Validators.parseUuid(rawBookingId, "id")
        val now = time.nowOffset()

        transactions.inTransaction {
            val booking = bookings.findById(bookingId)
                ?: throw NotFoundException("Booking not found")

            // Ownership before anything else: one user must never act on another's booking.
            if (booking.userId != userId) {
                throw ForbiddenException("This booking belongs to another user")
            }

            when (booking.status) {
                BookingStatus.CONFIRMED -> {
                    log.debug("Booking $bookingId is already confirmed; returning it unchanged")
                    return@inTransaction
                }

                BookingStatus.CANCELLED -> throw ConflictException(
                    errorCode = "BOOKING_CANCELLED",
                    message = "This booking was cancelled and cannot be confirmed",
                )

                BookingStatus.EXPIRED -> throw ConflictException(
                    errorCode = "HOLD_EXPIRED",
                    message = "This hold expired and the seats have been released. " +
                        "Please select your seats again.",
                )

                BookingStatus.PENDING -> Unit
            }

            if (booking.isHoldExpiredAt(now)) {
                // Lapsed but not yet swept. Close it out here so the user gets a clear
                // answer instead of the sweeper silently doing it later.
                val heldSeats = bookings.findActiveEventSeatIds(bookingId)
                bookings.deactivateSeats(bookingId)
                bookings.updateStatus(bookingId, BookingStatus.EXPIRED, clearHoldExpiry = true)
                if (heldSeats.isNotEmpty()) seats.markAvailable(heldSeats)

                log.info("Confirmation rejected: hold $bookingId expired at ${booking.holdExpiresAt}")
                throw ConflictException(
                    errorCode = "HOLD_EXPIRED",
                    message = "This hold expired at ${booking.holdExpiresAt}. " +
                        "The seats have been released - please select them again.",
                )
            }

            val heldSeatIds = bookings.findActiveEventSeatIds(bookingId)
            if (heldSeatIds.isEmpty()) {
                throw ConflictException(
                    errorCode = "BOOKING_HAS_NO_SEATS",
                    message = "This booking no longer holds any seats",
                )
            }

            // Re-lock and re-verify: the seats must still be held by this user.
            val locked = seats.lockEventSeatsForUpdate(booking.eventId, heldSeatIds)
            val stolen = locked.filterNot { row ->
                row.eventSeat.isClaimableBy(userId, now)
            }
            if (stolen.isNotEmpty()) {
                throw SeatsUnavailableException(
                    unavailableSeatIds = stolen.map { it.eventSeat.id.toString() },
                    message = "These seats are no longer held for you: " +
                        stolen.map { it.seat.label }.joinToString(", "),
                )
            }

            seats.markBooked(heldSeatIds)
            bookings.updateStatus(bookingId, BookingStatus.CONFIRMED, clearHoldExpiry = true)

            log.info("User $userId confirmed booking $bookingId (${heldSeatIds.size} seats)")
        }

        return loadDetail(bookingId)
    }

    /**
     * The caller's own bookings and holds, newest first.
     *
     * Read-only by design. A hold whose deadline has lapsed but which the sweeper
     * has not yet processed is still reported as PENDING with its (past)
     * `expiresAt`; the client can compare that instant against now to show it as
     * lapsed. Rewriting statuses from a GET would make a read request mutate data.
     */
    fun listMyBookings(userId: UUID): List<BookingResponse> =
        transactions.inTransaction { bookings.findDetailsByUser(userId) }
            .map { it.toResponse() }

    /**
     * One booking belonging to the caller.
     *
     * @throws NotFoundException if no such booking exists.
     * @throws ForbiddenException if it belongs to another user.
     */
    fun getBooking(userId: UUID, rawBookingId: String?): BookingResponse {
        val bookingId = Validators.parseUuid(rawBookingId, "id")

        val detail = transactions.inTransaction { bookings.findDetailById(bookingId) }
            ?: throw NotFoundException("Booking not found")

        if (detail.booking.userId != userId) {
            throw ForbiddenException("This booking belongs to another user")
        }
        return detail.toResponse()
    }

    /**
     * Cancels a hold or a confirmed booking and returns its seats to the market.
     *
     * One transaction releases the seats and updates the booking, so a seat is
     * never freed without the booking recording why - and never orphaned in
     * LOCKED/BOOKED with no live claim.
     *
     * Seat rows are deactivated rather than deleted: `uq_booking_seats_active`
     * only counts active rows, so the seat becomes sellable again while the
     * cancelled booking keeps its full seat history for the user and for admins.
     *
     * Idempotent - cancelling an already-cancelled booking returns it unchanged,
     * so a retried request is harmless.
     *
     * @throws NotFoundException if the booking (or its event) does not exist.
     * @throws ForbiddenException if the booking belongs to another user.
     * @throws ConflictException `EVENT_ALREADY_STARTED` for a confirmed booking on
     *         an event that has begun (spec section 8: cancel only before start
     *         time), or `HOLD_EXPIRED` for a hold that already lapsed.
     */
    fun cancelBooking(userId: UUID, rawBookingId: String?): BookingResponse {
        val bookingId = Validators.parseUuid(rawBookingId, "id")

        transactions.inTransaction {
            val booking = bookings.findById(bookingId)
                ?: throw NotFoundException("Booking not found")

            // Ownership before anything else: one user must never act on another's booking.
            if (booking.userId != userId) {
                throw ForbiddenException("This booking belongs to another user")
            }

            when (booking.status) {
                BookingStatus.CANCELLED -> {
                    log.debug("Booking $bookingId is already cancelled; returning it unchanged")
                    return@inTransaction
                }

                BookingStatus.EXPIRED -> throw ConflictException(
                    errorCode = "HOLD_EXPIRED",
                    message = "This hold already expired and its seats were released; " +
                        "there is nothing to cancel",
                )

                BookingStatus.CONFIRMED -> {
                    // Only a sold ticket has a cancellation deadline. Releasing a
                    // hold after the event started is harmless and still desirable,
                    // so PENDING is deliberately not subject to this check.
                    val event = events.findById(booking.eventId)
                        ?: throw NotFoundException("Event not found")

                    if (event.hasStartedAt(time.nowUtc())) {
                        log.info("Cancellation refused for $bookingId: event ${event.id} already started")
                        throw ConflictException(
                            errorCode = "EVENT_ALREADY_STARTED",
                            message = "This event has already started, so the booking " +
                                "can no longer be cancelled",
                        )
                    }
                }

                BookingStatus.PENDING -> Unit
            }

            // Free the seats and close the booking together.
            val claimedSeatIds = bookings.findActiveEventSeatIds(bookingId)
            bookings.deactivateSeats(bookingId)
            bookings.updateStatus(bookingId, BookingStatus.CANCELLED, clearHoldExpiry = true)
            if (claimedSeatIds.isNotEmpty()) {
                seats.markAvailable(claimedSeatIds)
            }

            log.info(
                "User $userId cancelled ${booking.status} booking $bookingId; " +
                    "released ${claimedSeatIds.size} seat(s)",
            )
        }

        return loadDetail(bookingId)
    }

    /**
     * Releases everything whose hold deadline has passed. Driven by [HoldSweeper].
     *
     * Two kinds of staleness are cleared, in this order:
     *
     * 1. PENDING bookings past `hold_expires_at` - marked EXPIRED, their seat claims
     *    deactivated and their seats returned to AVAILABLE.
     * 2. Seats still LOCKED past `lock_expires_at` with no live booking behind them.
     *    Nothing should produce these; sweeping them anyway means a seat can never be
     *    permanently stranded out of the market by a bug or a crashed transaction.
     *
     * Idempotent and safe to run concurrently with users booking: it only ever
     * touches rows that are already past their deadline, and a lapsed hold cannot be
     * confirmed anyway ([confirmBooking] re-checks expiry).
     *
     * [batchLimit] caps one pass so a large backlog cannot hold a connection for
     * minutes; the remainder is picked up on the next tick.
     */
    fun sweepExpiredHolds(batchLimit: Int = DEFAULT_SWEEP_BATCH): SweepResult {
        val now = time.nowOffset()

        return transactions.inTransaction {
            val expired = bookings.findExpiredHolds(now, batchLimit)

            var seatsReleased = 0
            expired.forEach { booking ->
                val heldSeatIds = bookings.findActiveEventSeatIds(booking.id)
                bookings.deactivateSeats(booking.id)
                bookings.updateStatus(booking.id, BookingStatus.EXPIRED, clearHoldExpiry = true)
                if (heldSeatIds.isNotEmpty()) {
                    seatsReleased += seats.markAvailable(heldSeatIds)
                }
                log.info(
                    "Hold ${booking.id} expired at ${booking.holdExpiresAt}; " +
                        "released ${heldSeatIds.size} seat(s)",
                )
            }

            // Runs after the holds above, whose seats now have null lock fields, so
            // whatever this finds is genuinely orphaned.
            val orphans = seats.releaseExpiredLocks(now)
            if (orphans > 0) {
                log.warn(
                    "Released $orphans seat(s) that were LOCKED past their deadline with " +
                        "no live hold behind them",
                )
            }

            SweepResult(
                holdsExpired = expired.size,
                seatsReleased = seatsReleased,
                orphanLocksReleased = orphans,
            )
        }
    }

    // ---------------- helpers ----------------
    /**
     * Releases any existing ACTIVE claim on [requestedIds] so the seats can be
     * re-claimed.
     *
     * Why this is necessary: `uq_booking_seats_active` allows exactly one active
     * `booking_seats` row per seat. By the time this runs the availability check has
     * already passed, so any surviving claim is one of:
     *
     *  - the caller's own live hold, being replaced by this new selection, or
     *  - a hold whose 24 hours lapsed but which the sweeper has not tidied yet.
     *
     * Both are stale, so the old booking is closed out and its seats freed. Without
     * this the insert would fail on the unique index and surface as a 500 rather
     * than working as the user expects.
     *
     * A superseded booking may include seats that are NOT part of the new request;
     * those are returned to AVAILABLE, since the caller has effectively replaced
     * that selection.
     */
    private fun releaseSupersededClaims(requestedIds: List<UUID>, userId: UUID) {
        val claimingBookingIds = bookings.findActiveClaimBookingIds(requestedIds)
        if (claimingBookingIds.isEmpty()) return

        claimingBookingIds.forEach { bookingId ->
            val previous = bookings.findById(bookingId) ?: return@forEach

            val previouslyHeld = bookings.findActiveEventSeatIds(bookingId)
            bookings.deactivateSeats(bookingId)

            val newStatus = if (previous.userId == userId) {
                // The caller replaced their own selection.
                BookingStatus.CANCELLED
            } else {
                // Someone else's hold that had already lapsed.
                BookingStatus.EXPIRED
            }
            bookings.updateStatus(bookingId, newStatus, clearHoldExpiry = true)

            // Seats from the old hold that are not in the new request go back on sale.
            val droppedSeats = previouslyHeld.filterNot { it in requestedIds }
            if (droppedSeats.isNotEmpty()) {
                seats.markAvailable(droppedSeats)
            }

            log.info(
                "Superseded booking $bookingId ($newStatus) to re-claim " +
                    "${requestedIds.size} seat(s); released ${droppedSeats.size} unrelated seat(s)",
            )
        }
    }

    /**
     * Validates the requested seat list.
     *
     * Duplicates are rejected rather than silently de-duplicated: a request for the
     * same seat twice means the client is confused, and quietly charging for one seat
     * would hide that.
     */
    private fun parseSeatIds(raw: List<String>?): List<UUID> {
        if (raw.isNullOrEmpty()) {
            throw ValidationException("eventSeatIds must contain at least one seat", "eventSeatIds")
        }
        if (raw.size > config.maxSeatsPerBooking) {
            throw ValidationException(
                "a booking may contain at most ${config.maxSeatsPerBooking} seats",
                "eventSeatIds",
            )
        }

        val ids = raw.map { Validators.parseUuid(it, "eventSeatIds") }

        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ValidationException(
                "eventSeatIds contains duplicates: ${duplicates.joinToString(", ")}",
                "eventSeatIds",
            )
        }
        return ids
    }

    /**
     * Generates a booking reference such as "TB-8F2K91".
     *
     * Created when the hold is placed, not at confirmation: `booking_reference` is
     * NOT NULL, and issuing the final reference up front means the user sees one
     * stable code for the whole flow instead of it changing on confirm.
     *
     * Uses [SecureRandom] over an unambiguous alphabet (no O/0 or I/1), and retries
     * on the astronomically unlikely collision. The UNIQUE constraint is the backstop.
     */
    private fun generateBookingReference(): String {
        repeat(REFERENCE_ATTEMPTS) {
            val suffix = (1..REFERENCE_LENGTH)
                .map { REFERENCE_ALPHABET[random.nextInt(REFERENCE_ALPHABET.length)] }
                .joinToString("")
            val candidate = "TB-$suffix"
            if (!bookings.existsByReference(candidate)) return candidate
        }
        // 36^6 possibilities; exhausting 10 attempts means something is badly wrong.
        error("Could not generate a unique booking reference after $REFERENCE_ATTEMPTS attempts")
    }

    /** Reloads a booking with event, venue and seat details for the response. */
    private fun loadDetail(bookingId: UUID): BookingResponse {
        val detail = transactions.inTransaction { bookings.findDetailById(bookingId) }
            ?: throw NotFoundException("Booking not found")
        return detail.toResponse()
    }

    private companion object {
        /** Excludes easily confused characters so a reference can be read aloud. */
        const val REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val REFERENCE_LENGTH = 6
        const val REFERENCE_ATTEMPTS = 10

        /** Holds processed per sweep. Large enough to keep up, small enough to stay quick. */
        const val DEFAULT_SWEEP_BATCH = 200
    }
}
