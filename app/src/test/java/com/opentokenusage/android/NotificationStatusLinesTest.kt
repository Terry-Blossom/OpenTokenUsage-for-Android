package com.opentokenusage.android

import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.util.statusNotificationLines
import com.opentokenusage.android.util.usageStatusEntries
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStatusLinesTest {
    @Test
    fun selectsPrimaryAndWeeklyUsageButSkipsDetailLines() {
        val snapshot = ProviderSnapshot(
            accountId = "a1",
            providerId = "claude",
            displayName = "Claude",
            fetchedAtMs = 1L,
            lines = listOf(
                MetricLine.Progress("Weekly (7d)", 35.0, 100.0, primaryOrder = 2, period = "weekly"),
                MetricLine.Progress("Session (5h)", 20.0, 100.0, primaryOrder = 1),
                MetricLine.Progress("Extra usage", 50.0, 100.0, format = MetricFormat.Percent, scope = "detail"),
            ),
        )

        assertEquals(listOf("Session (5h)", "Weekly (7d)"), statusNotificationLines(snapshot).map { it.label })
    }

    @Test
    fun weeklyOnlyProviderShowsOneLine() {
        val line = MetricLine.Progress("Total usage", 55.0, 100.0, period = "weekly")
        val snapshot = ProviderSnapshot(
            accountId = "a1",
            providerId = "cursor",
            displayName = "Cursor",
            fetchedAtMs = 1L,
            lines = listOf(line),
        )

        assertEquals(listOf(line), statusNotificationLines(snapshot))
    }

    @Test
    fun statusStripKeepsOneCellPerProviderAndCapsAtThree() {
        fun snap(id: String, provider: String) = ProviderSnapshot(
            accountId = id,
            providerId = provider,
            displayName = provider,
            fetchedAtMs = 1L,
            lines = listOf(MetricLine.Progress("Total usage", 25.0, 100.0, period = "weekly")),
        )
        val snapshots = listOf(
            snap("a1", "claude"),
            snap("a2", "claude"),
            snap("a3", "codex"),
            snap("a4", "cursor"),
            snap("a5", "grok"),
        )

        assertEquals(
            listOf("claude", "codex", "cursor"),
            usageStatusEntries(snapshots).map { it.snapshot.providerId },
        )
    }
}
