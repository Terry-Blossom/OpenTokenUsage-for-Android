package com.opentokenusage.android

import com.opentokenusage.android.domain.model.AccountAlertThresholds
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.util.evaluateAlertNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAlertThresholdsTest {
    private fun snapshot(accountId: String, displayName: String, usedPct: Double) = ProviderSnapshot(
        accountId = accountId,
        providerId = "claude",
        displayName = displayName,
        fetchedAtMs = 1_780_000_000_000L,
        lines = listOf(
            MetricLine.Progress(
                label = "Session (5h)",
                used = usedPct,
                limit = 100.0,
                resetsAtMs = 1_780_018_000_000L,
                primaryOrder = 1,
            ),
        ),
    )

    @Test
    fun usesGlobalThresholdsWhenAccountOverrideIsAbsent() {
        val settings = AppSettings(
            alertThreshold1 = 75,
            alertThreshold2 = 90,
            notifyThresholdAlerts = true,
        )
        assertEquals(75 to 90, settings.effectiveAlertThresholds("acc1"))

        val below = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc1", "Claude", 70.0),
            settings = settings,
            dedupeMap = emptyMap(),
            nowMs = 1_780_000_000_000L,
        )
        assertTrue(below.notifications.isEmpty())

        val hit = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc1", "Claude", 76.0),
            settings = settings,
            dedupeMap = emptyMap(),
            nowMs = 1_780_000_000_000L,
        )
        assertEquals(1, hit.notifications.size)
        assertTrue(hit.notifications.single().body.contains("75%"))
    }

    @Test
    fun appliesPerAccountThresholdOverrideWithoutAffectingOtherAccounts() {
        val settings = AppSettings(
            alertThreshold1 = 75,
            alertThreshold2 = 90,
            notifyThresholdAlerts = true,
        ).withAccountAlertThresholds(
            accountId = "acc_custom",
            thresholds = AccountAlertThresholds(threshold1 = 50, threshold2 = 80),
        )

        assertEquals(50 to 80, settings.effectiveAlertThresholds("acc_custom"))
        assertEquals(75 to 90, settings.effectiveAlertThresholds("acc_default"))

        // 55% triggers alert for acc_custom (override 50%), but not for acc_default (global 75%).
        val customLow = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc_custom", "Claude", 55.0),
            settings = settings,
            dedupeMap = emptyMap(),
            nowMs = 1_780_000_000_000L,
        )
        assertEquals(1, customLow.notifications.size)
        assertTrue(customLow.notifications.single().body.contains("50%"))

        val defaultLow = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc_default", "Codex", 55.0),
            settings = settings,
            dedupeMap = emptyMap(),
            nowMs = 1_780_000_000_000L,
        )
        assertTrue(defaultLow.notifications.isEmpty())

        // 82% triggers high threshold (80%) for acc_custom, only low threshold (75%) for acc_default.
        val customHigh = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc_custom", "Claude", 82.0),
            settings = settings,
            dedupeMap = customLow.updatedDedupeMap,
            nowMs = 1_780_000_100_000L,
        )
        assertEquals(1, customHigh.notifications.size)
        assertTrue(customHigh.notifications.single().body.contains("上限警告 80%"))

        val defaultMid = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc_default", "Codex", 82.0),
            settings = settings,
            dedupeMap = emptyMap(),
            nowMs = 1_780_000_100_000L,
        )
        assertEquals(1, defaultMid.notifications.size)
        assertTrue(defaultMid.notifications.single().body.contains("設定しきい値 75%"))
    }

    @Test
    fun supportsPartialOverrideReorderedThresholdsAndClearingToGlobal() {
        val partial = AppSettings(alertThreshold1 = 75, alertThreshold2 = 90)
            .withAccountAlertThresholds("acc1", AccountAlertThresholds(threshold1 = 60, threshold2 = null))
        assertEquals(60 to 90, partial.effectiveAlertThresholds("acc1"))

        val reversed = AppSettings(alertThreshold1 = 75, alertThreshold2 = 90, notifyThresholdAlerts = true)
            .withAccountAlertThresholds("acc1", AccountAlertThresholds(threshold1 = 85, threshold2 = 40))
        val res = evaluateAlertNotifications(
            previous = null,
            current = snapshot("acc1", "Claude", 45.0),
            settings = reversed,
            dedupeMap = emptyMap(),
            nowMs = 1_780_000_000_000L,
        )
        assertEquals(1, res.notifications.size)
        assertTrue(res.notifications.single().body.contains("40%"))

        val cleared = partial.withAccountAlertThresholds("acc1", null)
        assertFalse(cleared.accountAlertThresholds.containsKey("acc1"))
        assertEquals(75 to 90, cleared.effectiveAlertThresholds("acc1"))

        val clearedEmpty = partial.withAccountAlertThresholds("acc1", AccountAlertThresholds(null, null))
        assertTrue(clearedEmpty.accountAlertThresholds.isEmpty())
    }
}
