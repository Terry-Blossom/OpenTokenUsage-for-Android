package com.opentokenusage.android

import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.util.formatMonthlyStatusText
import com.opentokenusage.android.util.formatStatusPreviewText
import com.opentokenusage.android.util.isMonthlyStatusLine
import com.opentokenusage.android.util.selectStatusStripLines
import com.opentokenusage.android.util.statusNotificationLines
import com.opentokenusage.android.util.statusStripTextSizesSp
import com.opentokenusage.android.util.usageStatusEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStatusLinesTest {
    private val dayMs = 24L * 3600_000L

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
        // Even with monthly enabled, Claude has no monthly quota (Extra usage is not a monthly quota).
        assertEquals(
            listOf("Session (5h)", "Weekly (7d)"),
            statusNotificationLines(snapshot, includeMonthly = true).map { it.label },
        )
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
        assertEquals(listOf(line), statusNotificationLines(snapshot, includeMonthly = true))
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

    @Test
    fun includesDetailMonthlyLineOnlyWhenToggleEnabled() {
        val snapshot = ProviderSnapshot(
            accountId = "oc1",
            providerId = "opencode-go",
            displayName = "OpenCode Go",
            fetchedAtMs = 1L,
            lines = listOf(
                MetricLine.Progress("Session", 14.0, 100.0, periodDurationMs = 5L * 3600_000L, primaryOrder = 1),
                MetricLine.Progress("Weekly", 33.0, 100.0, periodDurationMs = 7L * dayMs, primaryOrder = 2, period = "weekly"),
                MetricLine.Progress("Monthly", 58.0, 100.0, periodDurationMs = 30L * dayMs, scope = "detail"),
            ),
        )

        val off = selectStatusStripLines(snapshot, includeMonthly = false)!!
        assertEquals("Session", off.primary.label)
        assertEquals("Weekly", off.weekly?.label)
        assertNull(off.monthly)
        assertEquals(listOf("Session", "Weekly"), statusNotificationLines(snapshot, includeMonthly = false).map { it.label })

        val on = selectStatusStripLines(snapshot, includeMonthly = true)!!
        assertEquals("Session", on.primary.label)
        assertEquals("Weekly", on.weekly?.label)
        assertEquals("Monthly", on.monthly?.label)
        assertEquals(listOf("Session", "Weekly", "Monthly"), statusNotificationLines(snapshot, includeMonthly = true).map { it.label })
    }

    @Test
    fun selectsOverviewMonthlyLineForKimi() {
        val snapshot = ProviderSnapshot(
            accountId = "k1",
            providerId = "kimi",
            displayName = "Kimi Code",
            fetchedAtMs = 1L,
            lines = listOf(
                MetricLine.Progress("5時間枠", 10.0, 100.0, periodDurationMs = 5L * 3600_000L, primaryOrder = 1),
                MetricLine.Progress("Weekly", 25.0, 100.0, periodDurationMs = 7L * dayMs, primaryOrder = 2, period = "weekly"),
                MetricLine.Progress("月間合計", 40.0, 100.0, periodDurationMs = 30L * dayMs, period = "monthly"),
            ),
        )

        val selected = selectStatusStripLines(snapshot, includeMonthly = true)!!
        assertEquals("5時間枠", selected.primary.label)
        assertEquals("Weekly", selected.weekly?.label)
        assertEquals("月間合計", selected.monthly?.label)
    }

    @Test
    fun monthlyDetectionMatchesPeriodDurationAndLabelWithoutFalsePositives() {
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("Quota", 10.0, 100.0, period = "monthly")))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("Quota", 10.0, 100.0, period = "Month")))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("Monthly", 10.0, 100.0)))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("1 month", 10.0, 100.0)))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("月間合計", 10.0, 100.0)))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("Cycle", 10.0, 100.0, periodDurationMs = 28L * dayMs)))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("Cycle", 10.0, 100.0, periodDurationMs = 30L * dayMs)))
        assertTrue(isMonthlyStatusLine(MetricLine.Progress("Cycle", 10.0, 100.0, periodDurationMs = 31L * dayMs)))

        assertFalse(isMonthlyStatusLine(MetricLine.Progress("Cycle", 10.0, 100.0, periodDurationMs = 27L * dayMs)))
        assertFalse(isMonthlyStatusLine(MetricLine.Progress("Cycle", 10.0, 100.0, periodDurationMs = 32L * dayMs)))
        assertFalse(isMonthlyStatusLine(MetricLine.Progress("Weekly", 10.0, 100.0, period = "weekly", periodDurationMs = 7L * dayMs)))
        assertFalse(isMonthlyStatusLine(MetricLine.Progress("Extra usage", 10.0, 100.0, scope = "detail")))
    }

    @Test
    fun monthlyPrimaryProviderDoesNotDuplicateOrMislabelSecondaryMonthlyLine() {
        val copilotSnapshot = ProviderSnapshot(
            accountId = "gh1",
            providerId = "copilot",
            displayName = "GitHub Copilot",
            fetchedAtMs = 1L,
            lines = listOf(
                MetricLine.Progress("Premium requests", 60.0, 300.0, periodDurationMs = 30L * dayMs, primaryOrder = 1),
                MetricLine.Progress("Chat", 10.0, 50.0, periodDurationMs = 30L * dayMs, primaryOrder = 2),
            ),
        )

        val selected = selectStatusStripLines(copilotSnapshot, includeMonthly = true)!!
        assertEquals("Premium requests", selected.primary.label)
        assertNull(selected.weekly)
        assertNull(selected.monthly)
    }

    @Test
    fun weeklyPrimaryProviderWithSeparateMonthlyLineDoesNotDuplicateWeekly() {
        val snapshot = ProviderSnapshot(
            accountId = "p1",
            providerId = "custom",
            displayName = "Custom",
            fetchedAtMs = 1L,
            lines = listOf(
                MetricLine.Progress("Weekly", 30.0, 100.0, periodDurationMs = 7L * dayMs, primaryOrder = 1, period = "weekly"),
                MetricLine.Progress("Monthly", 45.0, 100.0, periodDurationMs = 30L * dayMs, scope = "detail", period = "monthly"),
            ),
        )

        val selected = selectStatusStripLines(snapshot, includeMonthly = true)!!
        assertEquals("Weekly", selected.primary.label)
        assertNull(selected.weekly)
        assertEquals("Monthly", selected.monthly?.label)
    }

    @Test
    fun formatsMonthlyTextPreviewAndSmallerFontSizes() {
        val monthly = MetricLine.Progress("Monthly", 58.0, 100.0, periodDurationMs = 30L * dayMs, scope = "detail")
        assertEquals("42%", formatMonthlyStatusText(monthly, UsageDisplayMode.LEFT))
        assertEquals("58%", formatMonthlyStatusText(monthly, UsageDisplayMode.USED))
        assertFalse(formatMonthlyStatusText(monthly, UsageDisplayMode.LEFT).contains("月"))

        val snapshot = ProviderSnapshot(
            accountId = "oc1",
            providerId = "opencode-go",
            displayName = "OpenCode Go",
            fetchedAtMs = 1L,
            lines = listOf(
                MetricLine.Progress("Session", 14.0, 100.0, periodDurationMs = 5L * 3600_000L, primaryOrder = 1),
                MetricLine.Progress("Weekly", 33.0, 100.0, periodDurationMs = 7L * dayMs, primaryOrder = 2, period = "weekly"),
                monthly,
            ),
        )
        assertEquals(
            "OpenCode Go 86% / 67%",
            formatStatusPreviewText(usageStatusEntries(listOf(snapshot), includeMonthly = false), UsageDisplayMode.LEFT),
        )
        assertEquals(
            "OpenCode Go 86% / 67% / 42%",
            formatStatusPreviewText(usageStatusEntries(listOf(snapshot), includeMonthly = true), UsageDisplayMode.LEFT),
        )

        val legacySizes = statusStripTextSizesSp(hasWeekly = true, hasMonthly = false)
        assertEquals(14f, legacySizes.primarySp, 0.001f)
        assertEquals(12f, legacySizes.weeklySp, 0.001f)

        val threeLineSizes = statusStripTextSizesSp(hasWeekly = true, hasMonthly = true)
        assertTrue(threeLineSizes.monthlySp < threeLineSizes.weeklySp)
        assertTrue(threeLineSizes.weeklySp < threeLineSizes.primarySp)

        val twoLineMonthlySizes = statusStripTextSizesSp(hasWeekly = false, hasMonthly = true)
        assertTrue(twoLineMonthlySizes.monthlySp < legacySizes.weeklySp)
        assertTrue(twoLineMonthlySizes.monthlySp < twoLineMonthlySizes.primarySp)
    }
}
