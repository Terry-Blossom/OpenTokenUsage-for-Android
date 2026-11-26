package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.domain.model.AccountAlertThresholds
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import com.opentokenusage.android.domain.model.UsageDisplayMode
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies default configuration ships strictly without pre-populated mock accounts or demo data.
 */
class EmptyDefaultsTest {
    @Test
    fun defaultSettingsContainNoFakeDataAssumptions() {
        val defaults = AppSettings()
        assertEquals(UsageDisplayMode.LEFT, defaults.displayMode)
        assertEquals(RefreshIntervalOption.MIN_15, defaults.refreshInterval)
        assertFalse(defaults.showMonthlyUsage)
        assertFalse(defaults.notifyResetAlerts)
        assertTrue(defaults.notifyResetExpiryAlerts)
        assertTrue(defaults.alertThreshold1 in 1..99)
        assertTrue(defaults.alertThreshold2 in 1..99)
        assertTrue(defaults.accountAlertThresholds.isEmpty())
    }

    @Test
    fun settingsSerializationDefaultsAndPersistsShowMonthlyUsage() {
        val legacyDecoded = ProviderHttpSupport.json.decodeFromString<AppSettings>("""{"persistentNotificationEnabled":true}""")
        assertFalse(legacyDecoded.showMonthlyUsage)
        assertTrue(legacyDecoded.notifyResetExpiryAlerts)
        assertTrue(legacyDecoded.accountAlertThresholds.isEmpty())

        val encoded = ProviderHttpSupport.json.encodeToString(
            AppSettings(
                showMonthlyUsage = true,
                notifyResetExpiryAlerts = false,
                accountAlertThresholds = mapOf("acc1" to AccountAlertThresholds(60, 85)),
            ),
        )
        val roundTripped = ProviderHttpSupport.json.decodeFromString<AppSettings>(encoded)
        assertTrue(roundTripped.showMonthlyUsage)
        assertFalse(roundTripped.notifyResetExpiryAlerts)
        assertEquals(AccountAlertThresholds(60, 85), roundTripped.accountAlertThresholds["acc1"])
    }
}
