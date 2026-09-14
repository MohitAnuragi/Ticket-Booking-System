package com.ticketbooking.util

import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The single source of "now" for the whole application.
 *
 * Everything time-dependent - hold expiry, the cancellation window, the sweeper -
 * goes through this rather than calling [LocalDateTime.now] directly. That is what
 * makes those rules unit-testable: a test supplies a fixed or hand-advanced
 * [Clock] and asserts on exact boundaries instead of sleeping.
 *
 * TIMEZONE CONVENTION
 * The base schema stores `created_at`, `start_time` and `end_time` as
 * timezone-naive TIMESTAMP. This class always produces UTC values for those
 * columns, so naive timestamps are unambiguous as long as every write goes
 * through here. Columns added by db/02_deltas.sql are TIMESTAMPTZ and are
 * represented as [OffsetDateTime] at UTC offset.
 */
class TimeProvider(private val clock: Clock = Clock.systemUTC()) {

    /** Now, for timezone-naive TIMESTAMP columns. Always UTC. */
    fun nowUtc(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** Now, for TIMESTAMPTZ columns. */
    fun nowOffset(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** A deadline [hours] from now, for hold expiry. */
    fun offsetPlusHours(hours: Long): OffsetDateTime = nowOffset().plusHours(hours)

    companion object {
        /** Production instance. */
        val SYSTEM = TimeProvider()

        /** Builds a provider frozen at [at]; for tests. */
        fun fixedAt(at: OffsetDateTime): TimeProvider =
            TimeProvider(Clock.fixed(at.toInstant(), ZoneOffset.UTC))
    }
}
