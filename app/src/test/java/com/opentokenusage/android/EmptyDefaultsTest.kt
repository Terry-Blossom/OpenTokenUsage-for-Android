package com.opentokenusage.android

import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import com.opentokenusage.android.domain.model.UsageDisplayMode
import org.junit.Assert.assertEquals
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
        assertTrue(defaults.alertThreshold1 in 1..99)
        assertTrue(defaults.alertThreshold2 in 1..99)
    }
}
