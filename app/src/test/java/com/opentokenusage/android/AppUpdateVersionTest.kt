package com.opentokenusage.android

import com.opentokenusage.android.data.update.isNewerVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {
    @Test
    fun detectsNewerVersionTags() {
        assertTrue(isNewerVersion("0.1.9", "0.1.8"))
        assertTrue(isNewerVersion("v0.2.0", "0.1.9"))
        assertTrue(isNewerVersion("0.1.10", "0.1.9"))
        assertTrue(isNewerVersion("1.0", "0.9.9"))
    }

    @Test
    fun rejectsSameOrOlderVersions() {
        assertFalse(isNewerVersion("0.1.9", "0.1.9"))
        assertFalse(isNewerVersion("v0.1.8", "0.1.9"))
        assertFalse(isNewerVersion("0.1.9-beta", "0.1.9"))
    }
}
