package com.opentokenusage.android

import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.ResetCreditItem
import com.opentokenusage.android.util.evaluateAlertNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetExpiryNotificationTest {
    private val hourMs = 3600_000L
    private val dayMs = 24L * hourMs

    private fun snapshotWithReset(
        accountId: String = "acc1",
        providerId: String = "codex",
        displayName: String = "Codex",
        items: List<ResetCreditItem>,
        isStale: Boolean = false,
    ) = ProviderSnapshot(
        accountId = accountId,
        providerId = providerId,
        displayName = displayName,
        fetchedAtMs = 1_780_000_000_000L,
        isStale = isStale,
        lines = listOf(
            MetricLine.ResetCredit(
                label = "リセットクレジット",
                availableCount = items.sumOf { it.count },
                items = items,
                expiresAtMs = items.mapNotNull { it.expiresAtMs }.minOrNull(),
            ),
        ),
    )

    @Test
    fun notifiesAtSevenDaysAndTwentyFourHoursWithoutDuplicates() {
        val expiryMs = 1_790_000_000_000L
        val snapshot = snapshotWithReset(
            items = listOf(ResetCreditItem(count = 2, expiresAtMs = expiryMs)),
        )
        val settings = AppSettings(
            notifyThresholdAlerts = false,
            notifyPaceAlerts = false,
            notifyResetAlerts = false,
            notifyResetExpiryAlerts = true,
        )

        // 8 days before expiry -> no alert
        val res8d = evaluateAlertNotifications(null, snapshot, settings, emptyMap(), nowMs = expiryMs - 8L * dayMs)
        assertTrue(res8d.notifications.isEmpty())

        // 6 days before expiry -> 7d alert fires once
        val res6d = evaluateAlertNotifications(null, snapshot, settings, res8d.updatedDedupeMap, nowMs = expiryMs - 6L * dayMs)
        assertEquals(1, res6d.notifications.size)
        assertTrue(res6d.notifications.single().body.contains("7日以内"))
        assertTrue(res6d.updatedDedupeMap.keys.any { it.endsWith(":7d") })

        // 4 days before expiry -> deduplicated, no second 7d alert
        val res4d = evaluateAlertNotifications(null, snapshot, settings, res6d.updatedDedupeMap, nowMs = expiryMs - 4L * dayMs)
        assertTrue(res4d.notifications.isEmpty())

        // 20 hours before expiry -> 24h alert fires once
        val res20h = evaluateAlertNotifications(null, snapshot, settings, res4d.updatedDedupeMap, nowMs = expiryMs - 20L * hourMs)
        assertEquals(1, res20h.notifications.size)
        assertTrue(res20h.notifications.single().body.contains("24時間以内"))
        assertTrue(res20h.updatedDedupeMap.keys.any { it.endsWith(":24h") })

        // 10 hours before expiry -> deduplicated
        val res10h = evaluateAlertNotifications(null, snapshot, settings, res20h.updatedDedupeMap, nowMs = expiryMs - 10L * hourMs)
        assertTrue(res10h.notifications.isEmpty())
    }

    @Test
    fun firstSeenWithin24HoursFiresOnly24HourAlert() {
        val expiryMs = 1_790_000_000_000L
        val snapshot = snapshotWithReset(
            providerId = "claude",
            displayName = "Claude",
            items = listOf(ResetCreditItem(count = 1, expiresAtMs = expiryMs, kind = "fable")),
        )
        val settings = AppSettings(notifyResetExpiryAlerts = true)

        val res = evaluateAlertNotifications(null, snapshot, settings, emptyMap(), nowMs = expiryMs - 12L * hourMs)
        assertEquals(1, res.notifications.size)
        assertTrue(res.notifications.single().body.contains("Fable リセット"))
        assertTrue(res.notifications.single().body.contains("24時間以内"))
    }

    @Test
    fun respectsToggleAndSkipsUnknownExpiredOrStale() {
        val nowMs = 1_790_000_000_000L
        val snapshot = snapshotWithReset(
            items = listOf(
                ResetCreditItem(count = 1, expiresAtMs = nowMs + 3L * dayMs),
                ResetCreditItem(count = 1, expiresAtMs = null),
                ResetCreditItem(count = 1, expiresAtMs = nowMs - hourMs),
            ),
        )

        // Toggle off disables reset expiry alerts even when notifyResetAlerts is true
        val disabled = evaluateAlertNotifications(
            null,
            snapshot,
            AppSettings(notifyResetAlerts = true, notifyResetExpiryAlerts = false),
            emptyMap(),
            nowMs = nowMs,
        )
        assertTrue(disabled.notifications.isEmpty())

        // Stale snapshot does not fire
        val stale = evaluateAlertNotifications(
            null,
            snapshot.copy(isStale = true),
            AppSettings(notifyResetExpiryAlerts = true),
            emptyMap(),
            nowMs = nowMs,
        )
        assertTrue(stale.notifications.isEmpty())

        // Enabled fires only for the active dated item (skips null and expired)
        val enabled = evaluateAlertNotifications(
            null,
            snapshot,
            AppSettings(notifyResetExpiryAlerts = true),
            emptyMap(),
            nowMs = nowMs,
        )
        assertEquals(1, enabled.notifications.size)
    }

    @Test
    fun distinctKindsAtSameExpiryDeduplicateIndependently() {
        val nowMs = 1_790_000_000_000L
        val expiryMs = nowMs + 2L * dayMs
        val snapshot = snapshotWithReset(
            providerId = "claude",
            displayName = "Claude",
            items = listOf(
                ResetCreditItem(count = 1, expiresAtMs = expiryMs, kind = "fable"),
                ResetCreditItem(count = 2, expiresAtMs = expiryMs, kind = "weekly"),
            ),
        )

        val res = evaluateAlertNotifications(
            null,
            snapshot,
            AppSettings(notifyResetExpiryAlerts = true),
            emptyMap(),
            nowMs = nowMs,
        )
        assertEquals(2, res.notifications.size)
        assertTrue(res.notifications.any { it.body.contains("Fable リセット") })
        assertTrue(res.notifications.any { it.body.contains("週間枠リセット") })
    }
}
