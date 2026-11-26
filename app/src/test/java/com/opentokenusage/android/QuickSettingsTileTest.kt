package com.opentokenusage.android

import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.tile.buildQuickSettingsTilePresentation
import com.opentokenusage.android.tile.selectHighestPrimaryUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTileTest {
    private fun snapshot(
        id: String,
        providerId: String,
        displayName: String,
        lines: List<MetricLine>,
    ) = ProviderSnapshot(
        accountId = id,
        providerId = providerId,
        displayName = displayName,
        fetchedAtMs = 1_780_000_000_000L,
        lines = lines,
    )

    @Test
    fun selectsProviderWithHighestPrimaryUsageAndIgnoresDetailLines() {
        val claude = snapshot(
            id = "a1",
            providerId = "claude",
            displayName = "Claude",
            lines = listOf(
                MetricLine.Progress("Weekly", 95.0, 100.0, primaryOrder = 2, period = "weekly"),
                MetricLine.Progress("Session", 40.0, 100.0, primaryOrder = 1),
                MetricLine.Progress("Extra", 99.0, 100.0, scope = "detail"),
            ),
        )
        val codex = snapshot(
            id = "a2",
            providerId = "codex",
            displayName = "Codex",
            lines = listOf(
                MetricLine.Progress("5h", 82.0, 100.0, primaryOrder = 1),
                MetricLine.Progress("Weekly", 20.0, 100.0, primaryOrder = 2, period = "weekly"),
            ),
        )
        val cursor = snapshot(
            id = "a3",
            providerId = "cursor",
            displayName = "Cursor",
            lines = listOf(
                MetricLine.Text("Bonus", "$5"),
            ),
        )

        val highest = selectHighestPrimaryUsage(listOf(claude, codex, cursor))!!
        assertEquals("a2", highest.snapshot.accountId)
        assertEquals("5h", highest.primary.label)
        assertEquals(0.82, highest.primary.usedFraction, 0.0001)
    }

    @Test
    fun formatsSubtitleAccordingToDisplayMode() {
        val snapshots = listOf(
            snapshot(
                id = "a1",
                providerId = "claude",
                displayName = "Claude",
                lines = listOf(MetricLine.Progress("Session", 75.0, 100.0, primaryOrder = 1)),
            ),
            snapshot(
                id = "a2",
                providerId = "codex",
                displayName = "Codex",
                lines = listOf(MetricLine.Progress("5h", 30.0, 100.0, primaryOrder = 1)),
            ),
        )

        val leftMode = buildQuickSettingsTilePresentation(
            snapshots = snapshots,
            displayMode = UsageDisplayMode.LEFT,
            isRefreshing = false,
        )
        assertEquals("AI使用率", leftMode.label)
        assertEquals("Claude 残り 25%", leftMode.subtitle)
        assertTrue(leftMode.active)

        val usedMode = buildQuickSettingsTilePresentation(
            snapshots = snapshots,
            displayMode = UsageDisplayMode.USED,
            isRefreshing = false,
        )
        assertEquals("Claude 75% 使用", usedMode.subtitle)
        assertTrue(usedMode.active)
    }

    @Test
    fun handlesRefreshingUnconnectedAndPendingStates() {
        val refreshing = buildQuickSettingsTilePresentation(
            snapshots = emptyList(),
            displayMode = UsageDisplayMode.LEFT,
            isRefreshing = true,
        )
        assertEquals("更新中...", refreshing.subtitle)
        assertTrue(refreshing.active)

        val empty = buildQuickSettingsTilePresentation(
            snapshots = emptyList(),
            displayMode = UsageDisplayMode.LEFT,
            isRefreshing = false,
        )
        assertEquals("未接続", empty.subtitle)
        assertFalse(empty.active)
        assertNull(selectHighestPrimaryUsage(emptyList()))

        val pendingSnapshot = snapshot(
            id = "a1",
            providerId = "claude",
            displayName = "Claude",
            lines = emptyList(),
        )
        val pending = buildQuickSettingsTilePresentation(
            snapshots = listOf(pendingSnapshot),
            displayMode = UsageDisplayMode.LEFT,
            isRefreshing = false,
        )
        assertEquals("タップして更新", pending.subtitle)
        assertFalse(pending.active)
    }
}
