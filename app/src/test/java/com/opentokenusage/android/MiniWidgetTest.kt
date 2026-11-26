package com.opentokenusage.android

import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.widget.buildMiniWidgetDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniWidgetTest {
    @Test
    fun displaysPrimaryUsagePercentAccordingToDisplayMode() {
        val snapshot = ProviderSnapshot(
            accountId = "acc1",
            providerId = "claude",
            displayName = "Claude",
            fetchedAtMs = 1_780_000_000_000L,
            lines = listOf(
                MetricLine.Progress("Weekly", 80.0, 100.0, primaryOrder = 2, period = "weekly"),
                MetricLine.Progress("Session", 28.0, 100.0, primaryOrder = 1),
                MetricLine.Progress("Detail", 95.0, 100.0, scope = "detail"),
            ),
        )

        val left = buildMiniWidgetDisplay(snapshot, boundAccountId = "acc1", displayMode = UsageDisplayMode.LEFT)
        assertEquals("claude", left.providerId)
        assertEquals("Claude", left.displayName)
        assertEquals("72%", left.valueText)
        assertEquals(0.28, left.usedFraction!!, 0.0001)
        assertFalse(left.needsConfiguration)
        assertFalse(left.hasError)

        val used = buildMiniWidgetDisplay(snapshot, boundAccountId = "acc1", displayMode = UsageDisplayMode.USED)
        assertEquals("28%", used.valueText)
        assertEquals(0.28, used.usedFraction!!, 0.0001)
    }

    @Test
    fun promptsConfigurationWhenUnboundAndShowsPlaceholderWhenSnapshotMissing() {
        val unbound = buildMiniWidgetDisplay(null, boundAccountId = null, displayMode = UsageDisplayMode.LEFT)
        assertNull(unbound.providerId)
        assertEquals("設定", unbound.valueText)
        assertTrue(unbound.needsConfiguration)
        assertFalse(unbound.hasError)

        val missing = buildMiniWidgetDisplay(null, boundAccountId = "acc1", displayMode = UsageDisplayMode.LEFT)
        assertNull(missing.providerId)
        assertEquals("--", missing.valueText)
        assertFalse(missing.needsConfiguration)
        assertFalse(missing.hasError)
    }

    @Test
    fun handlesErrorAndNoProgressSnapshots() {
        val errorSnap = ProviderSnapshot(
            accountId = "acc1",
            providerId = "codex",
            displayName = "Codex",
            fetchedAtMs = 1_780_000_000_000L,
            lines = emptyList(),
            errorMessage = "認証エラー",
        )
        val errDisplay = buildMiniWidgetDisplay(errorSnap, boundAccountId = "acc1", displayMode = UsageDisplayMode.LEFT)
        assertEquals("codex", errDisplay.providerId)
        assertEquals("!", errDisplay.valueText)
        assertNull(errDisplay.usedFraction)
        assertTrue(errDisplay.hasError)

        val pendingSnap = errorSnap.copy(errorMessage = null)
        val pendingDisplay = buildMiniWidgetDisplay(pendingSnap, boundAccountId = "acc1", displayMode = UsageDisplayMode.LEFT)
        assertEquals("--", pendingDisplay.valueText)
        assertFalse(pendingDisplay.hasError)
    }
}
