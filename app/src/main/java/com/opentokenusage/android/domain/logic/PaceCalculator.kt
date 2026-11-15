package com.opentokenusage.android.domain.logic

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class PaceTier {
    AHEAD,
    ON_TRACK,
    BEHIND,
}

data class PaceResult(
    val tier: PaceTier,
    val projectedUsage: Double,
    val elapsedFraction: Double,
    val deficit: Double?,
    val etaToLimitMs: Long?,
)

/**
 * Computes usage pace status, projected end-of-period usage, deficit vs uniform rate,
 * and ETA to hitting 100%, matching desktop OpenTokenUsage pace math including Mon-Fri workday mode.
 */
object PaceCalculator {
    private const val MIN_ELAPSED_FRACTION = 0.05
    private const val DAY_MS = 86_400_000L
    private const val WEEK_MS = 7L * DAY_MS

    fun calculate(
        used: Double,
        limit: Double,
        resetsAtMs: Long?,
        periodDurationMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        workdays: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PaceResult? {
        if (limit <= 0.0 || resetsAtMs == null || periodDurationMs == null || periodDurationMs <= 0L) {
            return null
        }
        val periodStartMs = resetsAtMs - periodDurationMs
        if (nowMs >= resetsAtMs) return null

        val rawElapsedMs = nowMs - periodStartMs
        if (rawElapsedMs <= 0L) return null

        val useWorkdays = workdays && isWeeklyPeriod(periodDurationMs)
        val clampedNow = nowMs.coerceIn(periodStartMs, resetsAtMs)

        val (spentMs, totalActiveMs) = if (useWorkdays) {
            countWorkdayMs(periodStartMs, clampedNow, zoneId) to countWorkdayMs(periodStartMs, resetsAtMs, zoneId)
        } else {
            (clampedNow - periodStartMs) to periodDurationMs
        }

        if (totalActiveMs <= 0L) return null
        val elapsedFraction = (spentMs.toDouble() / totalActiveMs.toDouble()).coerceIn(0.0, 1.0)

        if (used <= 0.0) {
            return PaceResult(
                tier = PaceTier.AHEAD,
                projectedUsage = 0.0,
                elapsedFraction = elapsedFraction,
                deficit = null,
                etaToLimitMs = null,
            )
        }

        val projectedUsage = if (elapsedFraction > 0.0) used / elapsedFraction else used
        if (used >= limit) {
            val rawDeficit = used - elapsedFraction * limit
            return PaceResult(
                tier = PaceTier.BEHIND,
                projectedUsage = projectedUsage,
                elapsedFraction = elapsedFraction,
                deficit = if (rawDeficit > 0.0) rawDeficit else null,
                etaToLimitMs = null,
            )
        }

        if (elapsedFraction < MIN_ELAPSED_FRACTION) return null

        val tier = when {
            projectedUsage <= limit * 0.8 -> PaceTier.AHEAD
            projectedUsage <= limit -> PaceTier.ON_TRACK
            else -> PaceTier.BEHIND
        }

        val deficit = if (tier == PaceTier.BEHIND) {
            val diff = used - elapsedFraction * limit
            if (diff > 0.0) diff else null
        } else {
            null
        }

        val eta = if (tier == PaceTier.BEHIND) {
            computeEtaToLimitMs(
                used = used,
                limit = limit,
                nowMs = nowMs,
                resetsAtMs = resetsAtMs,
                spentMs = spentMs,
                totalActiveMs = totalActiveMs,
                useWorkdays = useWorkdays,
                zoneId = zoneId,
            )
        } else {
            null
        }

        return PaceResult(
            tier = tier,
            projectedUsage = projectedUsage,
            elapsedFraction = elapsedFraction,
            deficit = deficit,
            etaToLimitMs = eta,
        )
    }

    fun isWeeklyPeriod(periodDurationMs: Long): Boolean =
        abs(periodDurationMs - WEEK_MS) < DAY_MS

    internal fun countWorkdayMs(startMs: Long, endMs: Long, zoneId: ZoneId): Long {
        if (endMs <= startMs) return 0L
        var total = 0L
        var cursor = startMs
        while (cursor < endMs) {
            val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val nextMidnightMs = zoned.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sliceEnd = minOf(endMs, nextMidnightMs)
            val day = zoned.dayOfWeek
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                total += (sliceEnd - cursor)
            }
            cursor = sliceEnd
        }
        return total
    }

    private fun addWorkdayMs(fromMs: Long, neededWorkdayMs: Long, zoneId: ZoneId): Long {
        if (neededWorkdayMs <= 0L) return fromMs
        var remaining = neededWorkdayMs.toDouble()
        var cursor = fromMs
        var guard = 0
        while (remaining > 0.0 && guard++ < 60) {
            val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val nextMidnightMs = zoned.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sliceMs = (nextMidnightMs - cursor).toDouble()
            val day = zoned.dayOfWeek
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                if (remaining <= sliceMs) {
                    return cursor + remaining.toLong()
                }
                remaining -= sliceMs
            }
            cursor = nextMidnightMs
        }
        return cursor
    }

    private fun computeEtaToLimitMs(
        used: Double,
        limit: Double,
        nowMs: Long,
        resetsAtMs: Long,
        spentMs: Long,
        totalActiveMs: Long,
        useWorkdays: Boolean,
        zoneId: ZoneId,
    ): Long? {
        if (used <= 0.0 || limit <= used || spentMs <= 0L || totalActiveMs <= 0L) return null
        if (useWorkdays && spentMs.toDouble() < MIN_ELAPSED_FRACTION * totalActiveMs.toDouble()) return null
        val neededActiveMs = (((limit - used) / used) * spentMs.toDouble()).toLong().coerceAtLeast(0L)
        val etaEpochMs = if (useWorkdays) {
            addWorkdayMs(nowMs, neededActiveMs, zoneId)
        } else {
            nowMs + neededActiveMs
        }
        if (etaEpochMs >= resetsAtMs) return null
        return (etaEpochMs - nowMs).coerceAtLeast(0L)
    }
}
