package com.ticketbooking.repository.impl

import com.ticketbooking.model.Booking
import com.ticketbooking.model.BookingSeatDetail
import com.ticketbooking.model.BookingSeats
import com.ticketbooking.model.BookingStatus
import com.ticketbooking.model.Bookings
import com.ticketbooking.model.EventSeats
import com.ticketbooking.model.Events
import com.ticketbooking.model.SeatType
import com.ticketbooking.model.Seats
import com.ticketbooking.model.Users
import com.ticketbooking.model.Venues
import com.ticketbooking.repository.BookingDetail
import com.ticketbooking.repository.BookingFilter
import com.ticketbooking.repository.BookingRepository
import com.ticketbooking.repository.BookingSeatSpec
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PostgreSQL implementation of [BookingRepository].
 * Must be called inside a transaction.
 */
class PostgresBookingRepository : BookingRepository {

    override fun findById(id: UUID): Booking? =
        Bookings.selectAll()
            .where { Bookings.id eq id }
            .limit(1)
            .singleOrNull()
            ?.toBooking()

    override fun findDetailById(id: UUID): BookingDetail? {
        val row = (Bookings innerJoin Events innerJoin Venues)
            .selectAll()
            .where { Bookings.id eq id }
            .limit(1)
            .singleOrNull()
            ?: return null

        return row.toBookingDetail(seats = findSeatDetails(id))
    }

    override fun findDetailByIdWithUser(id: UUID): BookingDetail? {
        val row = (Bookings innerJoin Events innerJoin Venues innerJoin Users)
            .selectAll()
            .where { Bookings.id eq id }
            .limit(1)
            .singleOrNull()
            ?: return null

        return row.toBookingDetail(
            seats = findSeatDetails(id),
            userName = row[Users.name],
            userEmail = row[Users.email],
        )
    }

    override fun findDetailsByUser(userId: UUID): List<BookingDetail> {
        val rows = (Bookings innerJoin Events innerJoin Venues)
            .selectAll()
            .where { Bookings.userId eq userId }
            .orderBy(Bookings.createdAt to SortOrder.DESC)
            .toList()

        return rows.map { it.toBookingDetail(seats = findSeatDetails(it[Bookings.id])) }
    }

    override fun search(filter: BookingFilter): List<BookingDetail> {
        val rows = (Bookings innerJoin Events innerJoin Venues innerJoin Users)
            .selectAll()
            .where { filter.toCondition() }
            .orderBy(Bookings.createdAt to SortOrder.DESC)
            .limit(filter.limit)
            .offset(filter.offset)
            .toList()

        return rows.map { row ->
            row.toBookingDetail(
                seats = findSeatDetails(row[Bookings.id]),
                userName = row[Users.name],
                userEmail = row[Users.email],
            )
        }
    }

    override fun countMatching(filter: BookingFilter): Long =
        (Bookings innerJoin Events innerJoin Venues innerJoin Users)
            .selectAll()
            .where { filter.toCondition() }
            .count()

    private fun BookingFilter.toCondition(): Op<Boolean> {
        var condition: Op<Boolean> = Op.TRUE
        eventId?.let { condition = condition and (Bookings.eventId eq it) }
        userId?.let { condition = condition and (Bookings.userId eq it) }
        status?.let { condition = condition and (Bookings.status eq it.name) }
        return condition
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
        Bookings.insert {
            it[Bookings.id] = id
            it[Bookings.userId] = userId
            it[Bookings.eventId] = eventId
            it[Bookings.bookingReference] = bookingReference
            it[Bookings.status] = status.name
            it[Bookings.totalAmount] = totalAmount
            it[Bookings.createdAt] = createdAt
            it[Bookings.holdExpiresAt] = holdExpiresAt
        }

        // If another transaction already holds one of these seats, the partial unique
        // index uq_booking_seats_active rejects this insert and the whole transaction
        // rolls back. That is the database-level double-booking guarantee.
        if (seats.isNotEmpty()) {
            BookingSeats.batchInsert(seats, shouldReturnGeneratedValues = false) { spec ->
                this[BookingSeats.id] = UUID.randomUUID()
                this[BookingSeats.bookingId] = id
                this[BookingSeats.eventSeatId] = spec.eventSeatId
                this[BookingSeats.price] = spec.price
                this[BookingSeats.isActive] = true
            }
        }

        return Booking(
            id = id,
            userId = userId,
            eventId = eventId,
            bookingReference = bookingReference,
            status = status,
            totalAmount = totalAmount,
            createdAt = createdAt,
            holdExpiresAt = holdExpiresAt,
        )
    }

    override fun updateStatus(id: UUID, status: BookingStatus, clearHoldExpiry: Boolean): Boolean =
        Bookings.update({ Bookings.id eq id }) {
            it[Bookings.status] = status.name
            if (clearHoldExpiry) {
                // A confirmed or cancelled booking has no hold deadline; leaving a
                // stale value would let it be misread as an expired hold.
                it[Bookings.holdExpiresAt] = null
            }
        } > 0

    override fun updateReference(id: UUID, bookingReference: String): Boolean =
        Bookings.update({ Bookings.id eq id }) {
            it[Bookings.bookingReference] = bookingReference
        } > 0

    override fun existsByReference(bookingReference: String): Boolean =
        Bookings.selectAll()
            .where { Bookings.bookingReference eq bookingReference }
            .limit(1)
            .empty()
            .not()

    override fun findActiveEventSeatIds(bookingId: UUID): List<UUID> =
        BookingSeats.selectAll()
            .where { (BookingSeats.bookingId eq bookingId) and (BookingSeats.isActive eq true) }
            .map { it[BookingSeats.eventSeatId] }

    override fun findActiveClaimBookingIds(eventSeatIds: List<UUID>): Set<UUID> {
        if (eventSeatIds.isEmpty()) return emptySet()
        return BookingSeats.selectAll()
            .where {
                (BookingSeats.eventSeatId inList eventSeatIds) and (BookingSeats.isActive eq true)
            }
            .mapTo(mutableSetOf()) { it[BookingSeats.bookingId] }
    }

    override fun deactivateSeats(bookingId: UUID): Int =
        BookingSeats.update({
            (BookingSeats.bookingId eq bookingId) and (BookingSeats.isActive eq true)
        }) {
            // Frees the seat for resale while keeping the row as history.
            it[BookingSeats.isActive] = false
        }

    override fun findExpiredHolds(now: OffsetDateTime, limit: Int): List<Booking> =
        Bookings.selectAll()
            .where {
                (Bookings.status eq BookingStatus.PENDING.name) and
                    (Bookings.holdExpiresAt lessEq now)
            }
            .orderBy(Bookings.createdAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toBooking() }

    override fun countAll(): Long = Bookings.selectAll().count()

    /** Seats of a booking with their physical position, active rows first. */
    private fun findSeatDetails(bookingId: UUID): List<BookingSeatDetail> =
        (BookingSeats innerJoin EventSeats innerJoin Seats)
            .selectAll()
            .where { BookingSeats.bookingId eq bookingId }
            .orderBy(Seats.seatRow to SortOrder.ASC, Seats.seatNumber to SortOrder.ASC)
            .map { row ->
                BookingSeatDetail(
                    eventSeatId = row[BookingSeats.eventSeatId],
                    seatRow = row[Seats.seatRow],
                    seatNumber = row[Seats.seatNumber],
                    seatType = SeatType.from(row[Seats.seatType]),
                    price = row[BookingSeats.price],
                )
            }

    private fun ResultRow.toBooking() = Booking(
        id = this[Bookings.id],
        userId = this[Bookings.userId],
        eventId = this[Bookings.eventId],
        bookingReference = this[Bookings.bookingReference],
        status = BookingStatus.from(this[Bookings.status]),
        totalAmount = this[Bookings.totalAmount],
        createdAt = this[Bookings.createdAt],
        holdExpiresAt = this[Bookings.holdExpiresAt],
    )

    private fun ResultRow.toBookingDetail(
        seats: List<BookingSeatDetail>,
        userName: String? = null,
        userEmail: String? = null,
    ) = BookingDetail(
        booking = toBooking(),
        eventTitle = this[Events.title],
        eventStartTime = this[Events.startTime],
        venueName = this[Venues.name],
        venueCity = this[Venues.city],
        seats = seats,
        userName = userName,
        userEmail = userEmail,
    )
}
