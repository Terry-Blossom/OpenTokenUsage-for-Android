package com.opentokenusage.android

import com.opentokenusage.android.domain.logic.PaceCalculator
import com.opentokenusage.android.domain.logic.PaceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class PaceCalculatorTest {
    private val hourMs = 3_600_000L
    private val dayMs = 24L * hourMs
    private val weekMs = 7L * dayMs

    @Test
    fun zeroUsageIsAlwaysAhead() {
        val reset = 1_700_000_000_000L + 5 * hourMs
        val now = reset - 4 * hourMs
        val res = PaceCalculator.calculate(0.0, 100.0, reset, 5 * hourMs, now)
        assertNotNull(res)
        assertEquals(PaceTier.AHEAD, res!!.tier)
        assertEquals(0.0, res.projectedUsage, 0.001)
    }

    @Test
    fun belowFivePercentElapsedReturnsNullUnlessExhausted() {
        val period = 100L * 60_000L
        val reset = 1_700_000_000_000L + period
        val now = reset - 97L * 60_000L // 3% elapsed
        assertNull(PaceCalculator.calculate(10.0, 100.0, reset, period, now))

        val exhausted = PaceCalculator.calculate(100.0, 100.0, reset, period, now)
        assertNotNull(exhausted)
        assertEquals(PaceTier.BEHIND, exhausted!!.tier)
    }

    @Test
    fun classifiesAheadOnTrackAndBehindWithEta() {
        val period = 100L * hourMs
        val reset = 1_700_000_000_000L + period
        val now = reset - 50L * hourMs // 50% elapsed

        val ahead = PaceCalculator.calculate(35.0, 100.0, reset, period, now)!!
        assertEquals(PaceTier.AHEAD, ahead.tier)
        assertNull(ahead.deficit)

        val onTrack = PaceCalculator.calculate(48.0, 100.0, reset, period, now)!!
        assertEquals(PaceTier.ON_TRACK, onTrack.tier)
        assertNull(onTrack.etaToLimitMs)

        val behind = PaceCalculator.calculate(75.0, 100.0, reset, period, now)!!
        assertEquals(PaceTier.BEHIND, behind.tier)
        assertEquals(25.0, behind.deficit!!, 0.01)
        // At 75% in 50h -> reaches 100% in 16.66h (well before 50h reset)
        assertNotNull(behind.etaToLimitMs)
        assertTrue(behind.etaToLimitMs!! in 16L * hourMs..17L * hourMs)
    }

    @Test
    fun workdaysOnlyIgnoresSaturdayAndSunday() {
        // Mon 2026-06-01 00:00 UTC to Mon 2026-06-08 00:00 UTC (5 workdays = 120h)
        val start = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val reset = start + weekMs
        // Sun 2026-06-07 00:00 UTC -> exactly 5 workdays already elapsed out of 5 total workdays -> elapsedFraction = 1.0
        val sundayMidnight = start + 6L * dayMs
        val res = PaceCalculator.calculate(
            used = 90.0,
            limit = 100.0,
            resetsAtMs = reset,
            periodDurationMs = weekMs,
            nowMs = sundayMidnight,
            workdays = true,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(1.0, res.elapsedFraction, 0.001)
        assertEquals(PaceTier.ON_TRACK, res.tier)
    }
}
