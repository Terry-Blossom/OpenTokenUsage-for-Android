package com.opentokenusage.android.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentokenusage.android.OpenTokenUsageApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal fun isRefreshNowAction(action: String?): Boolean =
    action == NotificationRefreshReceiver.ACTION_REFRESH_NOW

internal suspend fun handleNotificationRefreshAction(
    action: String?,
    refresh: suspend () -> Unit,
): Boolean {
    if (!isRefreshNowAction(action)) return false
    refresh()
    return true
}

class NotificationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!isRefreshNowAction(intent?.action)) return
        val app = context.applicationContext as? OpenTokenUsageApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleNotificationRefreshAction(intent?.action) {
                    app.container.repository.initialize()
                    app.container.repository.refreshAll(isManual = true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_NOW = "com.opentokenusage.android.action.REFRESH_USAGE_NOW"

        fun createRefreshIntent(context: Context): Intent =
            Intent(context, NotificationRefreshReceiver::class.java).apply {
                action = ACTION_REFRESH_NOW
            }
    }
}
