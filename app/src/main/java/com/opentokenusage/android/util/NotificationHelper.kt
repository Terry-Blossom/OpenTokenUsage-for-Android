package com.opentokenusage.android.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.R
import com.opentokenusage.android.domain.logic.PaceCalculator
import com.opentokenusage.android.domain.logic.PaceTier
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.ui.theme.ProviderVisuals
import kotlin.math.roundToInt

class NotificationHelper(private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "使用量・リセット通知",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "クォータ上限への接近、超過ペース、リセット完了を通知します"
        }
        nm.createNotificationChannel(alertChannel)

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "使用量の常時表示",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "通知バーに最新の使用量を常時表示します"
            setShowBadge(false)
        }
        nm.createNotificationChannel(statusChannel)
    }

    /**
     * Keeps a single silent, non-dismissible notification in the shade with the latest
     * usage per account. Called whenever snapshots or settings change.
     */
    @SuppressLint("MissingPermission")
    fun updatePersistentNotification(
        snapshots: List<ProviderSnapshot>,
        settings: AppSettings,
    ) {
        if (!settings.persistentNotificationEnabled || snapshots.isEmpty()) {
            cancelPersistentNotification()
            return
        }
        if (!canPostNotifications()) return

        val rows = usageStatusEntries(snapshots, includeMonthly = settings.showMonthlyUsage)
        if (rows.isEmpty()) {
            cancelPersistentNotification()
            return
        }

        val baseTextColor = ContextCompat.getColor(context, R.color.notification_text)
        val stripBackground = if (ColorUtils.calculateLuminance(baseTextColor) < 0.5) {
            Color.WHITE
        } else {
            Color.rgb(32, 33, 36)
        }
        val darkStripBackground = ColorUtils.calculateLuminance(stripBackground) < 0.5

        val remoteViews = RemoteViews(context.packageName, R.layout.notification_usage_strip)
        val slots = listOf(
            StatusSlot(R.id.usage_slot_1, R.id.usage_icon_1, R.id.usage_percent_1, R.id.usage_weekly_1, R.id.usage_monthly_1),
            StatusSlot(R.id.usage_slot_2, R.id.usage_icon_2, R.id.usage_percent_2, R.id.usage_weekly_2, R.id.usage_monthly_2),
            StatusSlot(R.id.usage_slot_3, R.id.usage_icon_3, R.id.usage_percent_3, R.id.usage_weekly_3, R.id.usage_monthly_3),
        )
        rows.forEachIndexed { index, entry ->
            val slot = slots[index]
            val sizes = statusStripTextSizesSp(
                hasWeekly = entry.weekly != null,
                hasMonthly = entry.monthly != null,
            )
            remoteViews.setViewVisibility(slot.container, android.view.View.VISIBLE)
            remoteViews.setImageViewResource(slot.icon, ProviderVisuals.iconRes(entry.snapshot.providerId))
            remoteViews.setInt(
                slot.icon,
                "setColorFilter",
                usageStatusIconColor(entry.snapshot.providerId, darkStripBackground),
            )

            remoteViews.setViewVisibility(slot.primary, android.view.View.VISIBLE)
            remoteViews.setTextViewText(slot.primary, percentText(entry.primary, settings))
            remoteViews.setTextColor(
                slot.primary,
                usageStatusTextColor(entry.primary.usedFraction, baseTextColor, stripBackground),
            )
            if (entry.monthly != null) {
                remoteViews.setTextViewTextSize(slot.primary, TypedValue.COMPLEX_UNIT_SP, sizes.primarySp)
            }

            entry.weekly?.let { weekly ->
                remoteViews.setViewVisibility(slot.weekly, android.view.View.VISIBLE)
                remoteViews.setTextViewText(slot.weekly, percentText(weekly, settings))
                remoteViews.setTextColor(
                    slot.weekly,
                    usageStatusTextColor(weekly.usedFraction, baseTextColor, stripBackground),
                )
                if (entry.monthly != null) {
                    remoteViews.setTextViewTextSize(slot.weekly, TypedValue.COMPLEX_UNIT_SP, sizes.weeklySp)
                }
            }

            entry.monthly?.let { monthly ->
                remoteViews.setViewVisibility(slot.monthly, android.view.View.VISIBLE)
                remoteViews.setTextViewText(slot.monthly, formatMonthlyStatusText(monthly, settings.displayMode))
                remoteViews.setTextViewTextSize(slot.monthly, TypedValue.COMPLEX_UNIT_SP, sizes.monthlySp)
                remoteViews.setTextColor(
                    slot.monthly,
                    usageStatusTextColor(monthly.usedFraction, baseTextColor, stripBackground),
                )
            }
        }
        val preview = formatStatusPreviewText(rows, settings.displayMode)
        val updatedAt = snapshots.maxOfOrNull { it.fetchedAtMs }
            ?.takeIf { it > 0L }
            ?.let { "更新 ${UsageDisplayFormatter.formatTimeHm(it, settings.clock24h)}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_usage)
            .setContentTitle("OpenTokenUsage")
            .setContentText(preview)
            .setSubText(updatedAt)
            .setCustomContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(PERSISTENT_ID))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(PERSISTENT_ID, notification) }
    }

    fun cancelPersistentNotification() {
        runCatching { NotificationManagerCompat.from(context).cancel(PERSISTENT_ID) }
    }

    private fun percentText(line: MetricLine.Progress, settings: AppSettings): String =
        UsageDisplayFormatter.formatPercentShort(line.used, line.limit, settings.displayMode)

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

    @SuppressLint("MissingPermission")
    private fun postNotification(id: Int, title: String, body: String) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(id))
            .setAutoCancel(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ALERTS = "usage_alerts"
        const val CHANNEL_STATUS = "usage_status"
        private const val PERSISTENT_ID = 1201
    }

    private data class StatusSlot(
        val container: Int,
        val icon: Int,
        val primary: Int,
        val weekly: Int,
        val monthly: Int,
    )
}
