package com.ticketbooking.util

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TimeProvider] is the seam that makes every expiry rule testable, so its own
 * behaviour needs to be pinned down - particularly that it always produces UTC.
 */
class TimeProviderTest {

    private val fixed: OffsetDateTime = OffsetDateTime.of(2026, 9, 11, 12, 30, 0, 0, ZoneOffset.UTC)

    @Test
    fun `fixed provider reports the instant it was pinned to`() {
        val time = TimeProvider.fixedAt(fixed)

        assertEquals(fixed, time.nowOffset())
        assertEquals(fixed.toLocalDateTime(), time.nowUtc())
    }

    @Test
    fun `naive and offset views describe the same moment`() {
        val time = TimeProvider.fixedAt(fixed)

        // The LocalDateTime written to TIMESTAMP columns must be the UTC wall clock
        // of the same instant stored in TIMESTAMPTZ columns, otherwise the two
        // groups of columns would disagree about "now".
        assertEquals(time.nowOffset().toLocalDateTime(), time.nowUtc())
        assertEquals(ZoneOffset.UTC, time.nowOffset().offset)
    }

    @Test
    fun `offsetPlusHours computes the hold deadline`() {
        val time = TimeProvider.fixedAt(fixed)

        val deadline = time.offsetPlusHours(24)

        assertEquals(fixed.plusHours(24), deadline)
        assertEquals(OffsetDateTime.of(2026, 9, 12, 12, 30, 0, 0, ZoneOffset.UTC), deadline)
    }

    @Test
    fun `non-UTC input is normalised to UTC`() {
        // A provider pinned with an offset instant must still report UTC, so that
        // naive TIMESTAMP writes are unambiguous.
        val istMoment = OffsetDateTime.of(2026, 9, 11, 18, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))
        val time = TimeProvider.fixedAt(istMoment)

        assertEquals(ZoneOffset.UTC, time.nowOffset().offset)
        // 18:00+05:30 is 12:30 UTC.
        assertEquals(OffsetDateTime.of(2026, 9, 11, 12, 30, 0, 0, ZoneOffset.UTC), time.nowOffset())
    }
}
