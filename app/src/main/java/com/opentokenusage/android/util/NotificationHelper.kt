package com.opentokenusage.android.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.R
import com.opentokenusage.android.domain.logic.PaceCalculator
import com.opentokenusage.android.domain.logic.PaceTier
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import kotlin.math.roundToInt

class NotificationHelper(private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ALERTS,
            "使用量・リセット通知",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "クォータ上限への接近、超過ペース、リセット完了を通知します"
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Evaluates threshold, pace, and reset notifications and deduplicates across refreshes
     * so each condition fires once per reset window. Returns updated dedupe timestamps.
     */
    fun evaluateAndNotify(
        previous: ProviderSnapshot?,
        current: ProviderSnapshot,
        settings: AppSettings,
        dedupeMap: Map<String, Long>,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Long> {
        if (current.isStale || current.lines.isEmpty()) return dedupeMap
        val mutable = dedupeMap.toMutableMap()

        val progressLines = current.lines.filterIsInstance<MetricLine.Progress>()
        for (line in progressLines) {
            val pct = (line.usedFraction * 100.0).roundToInt()
            val windowKey = "${current.accountId}:${line.label}:${line.resetsAtMs ?: 0L}"

            if (settings.notifyThresholdAlerts) {
                val high = maxOf(settings.alertThreshold1, settings.alertThreshold2)
                val low = minOf(settings.alertThreshold1, settings.alertThreshold2)
                if (pct >= high && !mutable.containsKey("t2:$windowKey")) {
                    mutable["t2:$windowKey"] = nowMs
                    postNotification(
                        id = "t2:$windowKey".hashCode(),
                        title = "${current.displayName} (${line.label})",
                        body = "使用率が ${pct}% に到達しました（上限警告 ${high}%）",
                    )
                } else if (pct >= low && !mutable.containsKey("t1:$windowKey")) {
                    mutable["t1:$windowKey"] = nowMs
                    postNotification(
                        id = "t1:$windowKey".hashCode(),
                        title = "${current.displayName} (${line.label})",
                        body = "使用率が ${pct}% に達しました（設定しきい値 ${low}%）",
                    )
                }
            }

            if (settings.notifyPaceAlerts) {
                val pace = PaceCalculator.calculate(
                    used = line.used,
                    limit = line.limit,
                    resetsAtMs = line.resetsAtMs,
                    periodDurationMs = line.periodDurationMs,
                    nowMs = nowMs,
                    workdays = settings.workdaysOnlyWeeklyPace,
                )
                if (pace != null && pace.tier == PaceTier.BEHIND && pace.etaToLimitMs != null && !mutable.containsKey("pace:$windowKey")) {
                    mutable["pace:$windowKey"] = nowMs
                    postNotification(
                        id = "pace:$windowKey".hashCode(),
                        title = "${current.displayName}: ペース超過",
                        body = "${line.label} が現在のペースではリセット前に上限に達します",
                    )
                }
            }
        }

        if (settings.notifyResetAlerts && previous != null) {
            val prevMap = previous.lines.filterIsInstance<MetricLine.Progress>().associateBy { it.label }
            for (curLine in progressLines) {
                val prevLine = prevMap[curLine.label] ?: continue
                if (prevLine.usedFraction >= 0.25 && curLine.usedFraction <= 0.05 && prevLine.resetsAtMs != curLine.resetsAtMs) {
                    val resetKey = "reset:${current.accountId}:${curLine.label}:${curLine.resetsAtMs ?: nowMs}"
                    if (!mutable.containsKey(resetKey)) {
                        mutable[resetKey] = nowMs
                        postNotification(
                            id = resetKey.hashCode(),
                            title = "${current.displayName}: リセット完了",
                            body = "${curLine.label} の利用枠がリセットされました",
                        )
                    }
                }
            }
        }

        // Prune keys older than 30 days
        val cutoff = nowMs - 30L * 24L * 3600_000L
        return mutable.filterValues { it >= cutoff }
    }

    private fun postNotification(id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        const val CHANNEL_ALERTS = "usage_alerts"
    }
}
