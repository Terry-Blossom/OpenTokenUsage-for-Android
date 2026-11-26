package com.opentokenusage.android

import com.opentokenusage.android.util.NotificationHelper
import com.opentokenusage.android.util.NotificationRefreshReceiver
import com.opentokenusage.android.util.handleNotificationRefreshAction
import com.opentokenusage.android.util.isRefreshNowAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRefreshActionTest {
    @Test
    fun matchesOnlyRefreshNowAction() {
        assertTrue(isRefreshNowAction(NotificationRefreshReceiver.ACTION_REFRESH_NOW))
        assertFalse(isRefreshNowAction(null))
        assertFalse(isRefreshNowAction(""))
        assertFalse(isRefreshNowAction("com.opentokenusage.android.action.OTHER"))
        assertEquals("今すぐ更新", NotificationHelper.ACTION_REFRESH_NOW_LABEL)
    }

    @Test
    fun triggersRefreshOnlyForRefreshNowAction() = runTest {
        var count = 0

        assertFalse(handleNotificationRefreshAction(null) { count++ })
        assertFalse(handleNotificationRefreshAction("other.action") { count++ })
        assertEquals(0, count)

        assertTrue(handleNotificationRefreshAction(NotificationRefreshReceiver.ACTION_REFRESH_NOW) { count++ })
        assertEquals(1, count)
    }
}
