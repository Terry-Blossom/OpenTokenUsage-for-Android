package com.opentokenusage.android

import com.opentokenusage.android.data.storage.RateLimitGateStore
import com.opentokenusage.android.domain.logic.SmartRefreshPolicy
import com.opentokenusage.android.domain.logic.SmartRefreshState
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRefreshPolicyTest {
    private fun snapshot(
        accountId: String = "acc1",
        sessionUsed: Double,
        sessionLimit: Double = 100.0,
        weeklyUsed: Double = 30.0,
        note: String = "v1",
    ) = ProviderSnapshot(
        accountId = accountId,
        providerId = "codex",
        displayName = "Codex",
        fetchedAtMs = 1_000_000L,
        lines = listOf(
            MetricLine.Progress("Session (5h)", sessionUsed, sessionLimit),
            MetricLine.Progress("Weekly (7d)", weeklyUsed, 100.0),
            MetricLine.Text("Note", note),
        ),
    )

    @Test
    fun detectsUsageChangesOnProgressUsedOrLimitOnly() {
        val first = snapshot(sessionUsed = 20.0)
        assertTrue(SmartRefreshPolicy.hasProgressUsageChanged(null, first))

        val sameWithDifferentText = snapshot(sessionUsed = 20.0, note = "v2")
        assertFalse(SmartRefreshPolicy.hasProgressUsageChanged(first, sameWithDifferentText))

        val usedChanged = snapshot(sessionUsed = 21.0)
        assertTrue(SmartRefreshPolicy.hasProgressUsageChanged(first, usedChanged))

        val limitChanged = snapshot(sessionUsed = 20.0, sessionLimit = 200.0)
        assertTrue(SmartRefreshPolicy.hasProgressUsageChanged(first, limitChanged))
    }

    @Test
    fun smartIntervalSteps2To5To10To15WhenUnchangedAndResetsTo2OnChange() {
        val t0 = 1_000_000L
        // Initial change -> 2m
        var state = SmartRefreshPolicy.onSuccess(SmartRefreshState(), usageChanged = true, nowMs = t0)
        assertEquals(0, state.unchangedStreak)
        assertEquals(t0 + 2 * 60_000L, state.nextRefreshAtMs)

        // 1st unchanged -> 5m
        val t1 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onSuccess(state, usageChanged = false, nowMs = t1)
        assertEquals(1, state.unchangedStreak)
        assertEquals(t1 + 5 * 60_000L, state.nextRefreshAtMs)

        // 2nd unchanged -> 10m
        val t2 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onSuccess(state, usageChanged = false, nowMs = t2)
        assertEquals(2, state.unchangedStreak)
        assertEquals(t2 + 10 * 60_000L, state.nextRefreshAtMs)

        // 3rd unchanged -> 15m
        val t3 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onSuccess(state, usageChanged = false, nowMs = t3)
        assertEquals(3, state.unchangedStreak)
        assertEquals(t3 + 15 * 60_000L, state.nextRefreshAtMs)

        // 4th unchanged stays capped at 15m
        val t4 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onSuccess(state, usageChanged = false, nowMs = t4)
        assertEquals(3, state.unchangedStreak)
        assertEquals(t4 + 15 * 60_000L, state.nextRefreshAtMs)

        // Usage changes -> drops back to 2m
        val t5 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onSuccess(state, usageChanged = true, nowMs = t5)
        assertEquals(0, state.unchangedStreak)
        assertEquals(t5 + 2 * 60_000L, state.nextRefreshAtMs)
    }

    @Test
    fun failureBackoffUsesSeparate2To5To10To15ScheduleAndResetsOnSuccess() {
        val t0 = 2_000_000L
        var state = SmartRefreshState(unchangedStreak = 2, failureStreak = 0, nextRefreshAtMs = t0)

        state = SmartRefreshPolicy.onFailure(state, nowMs = t0)
        assertEquals(1, state.failureStreak)
        assertEquals(2, state.unchangedStreak)
        assertEquals(t0 + 2 * 60_000L, state.nextRefreshAtMs)

        val t1 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onFailure(state, nowMs = t1)
        assertEquals(2, state.failureStreak)
        assertEquals(t1 + 5 * 60_000L, state.nextRefreshAtMs)

        val t2 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onFailure(state, nowMs = t2)
        assertEquals(3, state.failureStreak)
        assertEquals(t2 + 10 * 60_000L, state.nextRefreshAtMs)

        val t3 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onFailure(state, nowMs = t3)
        assertEquals(4, state.failureStreak)
        assertEquals(t3 + 15 * 60_000L, state.nextRefreshAtMs)

        val t4 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onFailure(state, nowMs = t4)
        assertEquals(4, state.failureStreak)
        assertEquals(t4 + 15 * 60_000L, state.nextRefreshAtMs)

        // Recovery resets failureStreak to 0
        val t5 = state.nextRefreshAtMs
        state = SmartRefreshPolicy.onSuccess(state, usageChanged = true, nowMs = t5)
        assertEquals(0, state.failureStreak)
        assertEquals(0, state.unchangedStreak)
        assertEquals(t5 + 2 * 60_000L, state.nextRefreshAtMs)
    }

    @Test
    fun respectsRateLimitGateMinGapAndBlockedDelay() {
        val now = 5_000_000L
        val claudeMinGap = RateLimitGateStore.minimumAutoGapMs("claude")
        assertEquals(10 * 60_000L, claudeMinGap)

        // Even when usage changed (2m), Claude respects 10m minimum auto gap.
        val afterSuccess = SmartRefreshPolicy.onSuccess(
            current = SmartRefreshState(),
            usageChanged = true,
            nowMs = now,
            minGapMs = claudeMinGap,
        )
        assertEquals(now + 10 * 60_000L, afterSuccess.nextRefreshAtMs)

        // Gate block postpones nextRefreshAtMs without mutating streaks.
        val blocked = SmartRefreshPolicy.onGateBlocked(
            current = SmartRefreshState(unchangedStreak = 1, failureStreak = 0, nextRefreshAtMs = now),
            waitMs = 12 * 60_000L,
            nowMs = now,
        )
        assertEquals(1, blocked.unchangedStreak)
        assertEquals(0, blocked.failureStreak)
        assertEquals(now + 12 * 60_000L, blocked.nextRefreshAtMs)
    }

    @Test
    fun computesForegroundDelaysForMin5SmartAndManual() {
        val now = 10_000_000L
        // MANUAL returns null
        assertNull(
            SmartRefreshPolicy.nextForegroundDelayMs(
                interval = RefreshIntervalOption.MANUAL,
                lastSyncMs = now,
                smartStates = emptyMap(),
                enabledAccountIds = listOf("a1"),
                nowMs = now,
            ),
        )

        // MIN_5 waits remaining time until 5 minutes after lastSyncMs
        assertEquals(
            3 * 60_000L,
            SmartRefreshPolicy.nextForegroundDelayMs(
                interval = RefreshIntervalOption.MIN_5,
                lastSyncMs = now - 2 * 60_000L,
                smartStates = emptyMap(),
                enabledAccountIds = listOf("a1"),
                nowMs = now,
            ),
        )
        assertEquals(
            0L,
            SmartRefreshPolicy.nextForegroundDelayMs(
                interval = RefreshIntervalOption.MIN_5,
                lastSyncMs = now - 6 * 60_000L,
                smartStates = emptyMap(),
                enabledAccountIds = listOf("a1"),
                nowMs = now,
            ),
        )

        // SMART wakes up for whichever enabled account is due earliest
        val smartStates = mapOf(
            "a1" to SmartRefreshState(nextRefreshAtMs = now + 5 * 60_000L),
            "a2" to SmartRefreshState(nextRefreshAtMs = now + 2 * 60_000L),
        )
        assertEquals(
            2 * 60_000L,
            SmartRefreshPolicy.nextForegroundDelayMs(
                interval = RefreshIntervalOption.SMART,
                lastSyncMs = now,
                smartStates = smartStates,
                enabledAccountIds = listOf("a1", "a2"),
                nowMs = now,
            ),
        )

        // Unseen enabled account is immediately due (0ms)
        assertEquals(
            0L,
            SmartRefreshPolicy.nextForegroundDelayMs(
                interval = RefreshIntervalOption.SMART,
                lastSyncMs = now,
                smartStates = smartStates,
                enabledAccountIds = listOf("a1", "a3"),
                nowMs = now,
            ),
        )
    }
}
