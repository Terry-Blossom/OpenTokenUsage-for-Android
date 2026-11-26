package com.opentokenusage.android.domain.logic

import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import kotlin.math.abs

/**
 * Per-account adaptive scheduling state for [RefreshIntervalOption.SMART].
 */
data class SmartRefreshState(
    val unchangedStreak: Int = 0,
    val failureStreak: Int = 0,
    val nextRefreshAtMs: Long = 0L,
)

/**
 * Pure scheduling calculations for foreground refresh modes:
 * - [RefreshIntervalOption.MIN_5]: 5-minute cadence while the app is STARTED (WorkManager 15m in background).
 * - [RefreshIntervalOption.SMART]: per-account adaptive interval:
 *   - Usage changed -> 2 minutes.
 *   - Consecutive unchanged refreshes -> 2 -> 5 -> 10 -> 15 minutes.
 *   - Consecutive failures -> separate backoff 2 -> 5 -> 10 -> 15 minutes.
 *   - Respects existing [com.opentokenusage.android.data.storage.RateLimitGateStore] minimum gaps / 429 gates.
 */
object SmartRefreshPolicy {
    val UNCHANGED_INTERVAL_MINUTES: List<Int> = listOf(2, 5, 10, 15)
    val FAILURE_BACKOFF_MINUTES: List<Int> = listOf(2, 5, 10, 15)
    private const val USAGE_EPSILON = 1e-6

    fun hasProgressUsageChanged(
        previous: ProviderSnapshot?,
        current: ProviderSnapshot,
    ): Boolean {
        if (previous == null) return true
        val prevProgress = previous.lines.filterIsInstance<MetricLine.Progress>().sortedBy { it.label }
        val curProgress = current.lines.filterIsInstance<MetricLine.Progress>().sortedBy { it.label }
        if (prevProgress.size != curProgress.size) return true
        return prevProgress.zip(curProgress).any { (prev, cur) ->
            prev.label != cur.label ||
                abs(prev.used - cur.used) > USAGE_EPSILON ||
                abs(prev.limit - cur.limit) > USAGE_EPSILON
        }
    }

    /** Returns (nextUnchangedStreak, intervalMinutes). */
    fun nextIntervalMinutesOnSuccess(
        previousUnchangedStreak: Int,
        usageChanged: Boolean,
    ): Pair<Int, Int> {
        if (usageChanged) {
            return 0 to UNCHANGED_INTERVAL_MINUTES.first()
        }
        val nextStreak = (previousUnchangedStreak.coerceAtLeast(0) + 1)
            .coerceAtMost(UNCHANGED_INTERVAL_MINUTES.lastIndex)
        return nextStreak to UNCHANGED_INTERVAL_MINUTES[nextStreak]
    }

    /** Returns (nextFailureStreak, backoffMinutes). */
    fun nextIntervalMinutesOnFailure(previousFailureStreak: Int): Pair<Int, Int> {
        val nextStreak = (previousFailureStreak.coerceAtLeast(0) + 1)
            .coerceAtMost(FAILURE_BACKOFF_MINUTES.size)
        val minutes = FAILURE_BACKOFF_MINUTES[(nextStreak - 1).coerceIn(0, FAILURE_BACKOFF_MINUTES.lastIndex)]
        return nextStreak to minutes
    }

    fun onSuccess(
        current: SmartRefreshState,
        usageChanged: Boolean,
        nowMs: Long,
        minGapMs: Long = 0L,
    ): SmartRefreshState {
        val (nextUnchanged, minutes) = nextIntervalMinutesOnSuccess(
            previousUnchangedStreak = current.unchangedStreak,
            usageChanged = usageChanged,
        )
        val delayMs = maxOf(minutes * 60_000L, minGapMs.coerceAtLeast(0L))
        return SmartRefreshState(
            unchangedStreak = nextUnchanged,
            failureStreak = 0,
            nextRefreshAtMs = nowMs + delayMs,
        )
    }

    fun onFailure(
        current: SmartRefreshState,
        nowMs: Long,
        minDelayMs: Long = 0L,
    ): SmartRefreshState {
        val (nextFailure, minutes) = nextIntervalMinutesOnFailure(current.failureStreak)
        val delayMs = maxOf(minutes * 60_000L, minDelayMs.coerceAtLeast(0L))
        return current.copy(
            failureStreak = nextFailure,
            nextRefreshAtMs = nowMs + delayMs,
        )
    }

    fun onGateBlocked(
        current: SmartRefreshState,
        waitMs: Long,
        nowMs: Long,
    ): SmartRefreshState {
        val blockedUntil = nowMs + waitMs.coerceAtLeast(1_000L)
        return current.copy(nextRefreshAtMs = maxOf(current.nextRefreshAtMs, blockedUntil))
    }

    fun isDue(state: SmartRefreshState?, nowMs: Long): Boolean =
        state == null || state.nextRefreshAtMs <= nowMs

    fun nextForegroundDelayMs(
        interval: RefreshIntervalOption,
        lastSyncMs: Long?,
        smartStates: Map<String, SmartRefreshState>,
        enabledAccountIds: List<String>,
        nowMs: Long,
    ): Long? {
        if (enabledAccountIds.isEmpty() || interval == RefreshIntervalOption.MANUAL) return null
        if (interval == RefreshIntervalOption.SMART) {
            var earliestRemaining = Long.MAX_VALUE
            for (accountId in enabledAccountIds) {
                val state = smartStates[accountId]
                if (isDue(state, nowMs)) return 0L
                val remaining = (state!!.nextRefreshAtMs - nowMs).coerceAtLeast(0L)
                if (remaining < earliestRemaining) earliestRemaining = remaining
            }
            return if (earliestRemaining == Long.MAX_VALUE) null else earliestRemaining
        }
        val periodMinutes = interval.minutes ?: return null
        val periodMs = periodMinutes * 60_000L
        if (lastSyncMs == null) return 0L
        return (lastSyncMs + periodMs - nowMs).coerceAtLeast(0L)
    }
}
