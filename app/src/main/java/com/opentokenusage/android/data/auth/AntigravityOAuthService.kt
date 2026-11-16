package com.opentokenusage.android.data.auth

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.R
import com.opentokenusage.android.util.NotificationHelper

/**
 * Short-lived foreground service active only while waiting for the Google OAuth browser redirect,
 * preventing Android 14+ Cached App Freezer from suspending the local callback socket mid-login.
 */
class AntigravityOAuthService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_usage)
            .setContentTitle("Antigravity ログイン待機中")
            .setContentText("ブラウザで Google ログインを完了してください")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1202
        private const val ACTION_STOP = "com.opentokenusage.android.action.STOP_ANTIGRAVITY_OAUTH"

        fun start(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(appCtx, Intent(appCtx, AntigravityOAuthService::class.java))
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, AntigravityOAuthService::class.java))
            }
        }
    }
}
