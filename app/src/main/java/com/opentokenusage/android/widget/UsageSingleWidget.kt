package com.opentokenusage.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.OpenTokenUsageApp
import com.opentokenusage.android.R
import com.opentokenusage.android.domain.logic.PaceCalculator
import com.opentokenusage.android.domain.logic.PaceTier
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.ui.theme.ProviderVisuals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UsageSingleWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(120.dp, 56.dp),
            DpSize(220.dp, 120.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? OpenTokenUsageApp
        val repo = app?.container?.repository
        repo?.initialize()

        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrNull()

        val settings = repo?.settings?.value ?: AppSettings()
        val boundAccountId = appWidgetId?.let { repo?.getWidgetBinding(it) }
        val allSnapshots = repo?.orderedDisplaySnapshots().orEmpty()
        val selectedSnapshot = boundAccountId?.let { targetId ->
            allSnapshots.firstOrNull { it.accountId == targetId }
        }
        val nowMs = System.currentTimeMillis()
        val isDark = WidgetPalette.isDarkStatic(context, settings.themeMode)

        provideContent {
            SingleWidgetContent(
                context = context,
                appWidgetId = appWidgetId,
                boundAccountId = boundAccountId,
                snapshot = selectedSnapshot,
                settings = settings,
                nowMs = nowMs,
                isDark = isDark,
            )
        }
    }
}

@Composable
private fun SingleWidgetContent(
    context: Context,
    appWidgetId: Int?,
    boundAccountId: String?,
    snapshot: ProviderSnapshot?,
    settings: AppSettings,
    nowMs: Long,
    isDark: Boolean,
) {
    val size = LocalSize.current
    val isTall = size.height >= 100.dp
    val themeMode = settings.themeMode
    val primaryColor = WidgetPalette.textPrimary(themeMode)
    val mutedColor = WidgetPalette.textMuted(themeMode)

    val configIntent = Intent(context, WidgetConfigureActivity::class.java).apply {
        if (appWidgetId != null) putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    if (snapshot == null) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetPalette.bg(themeMode))
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(if (boundAccountId == null) configIntent else openAppIntent))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (boundAccountId == null) "プロバイダー未選択" else "データ未設定",
                style = TextStyle(color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = "タップして設定",
                style = TextStyle(color = mutedColor, fontSize = 10.sp),
                maxLines = 1,
            )
        }
        return
    }

    val iconColor = ProviderVisuals.iconTint(snapshot.providerId, isDark)
    val fillArgb = ProviderVisuals.barColor(snapshot.providerId, isDark).toArgb()
    val trackArgb = if (isDark) Color(0x28FFFFFF).toArgb() else Color(0x1C000000).toArgb()
    val markerArgb = if (isDark) Color(0xFFFFFFFF).toArgb() else Color(0xFF1A1A1A).toArgb()
    val tickArgb = if (isDark) Color(0x55FFFFFF).toArgb() else Color(0x44000000).toArgb()

    val maxProgressCount = if (isTall) 2 else 1
    val progressLines = snapshot.primaryProgressLines(maxCount = maxProgressCount)
    val fallbackText = snapshot.overviewLines().firstOrNull { it is MetricLine.Text } as? MetricLine.Text

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetPalette.bg(themeMode))
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(openAppIntent))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(ProviderVisuals.iconRes(snapshot.providerId)),
                contentDescription = snapshot.displayName,
                colorFilter = ColorFilter.tint(ColorProvider(iconColor)),
                modifier = GlanceModifier.size(15.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = snapshot.displayName,
                style = TextStyle(color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Box(
                modifier = GlanceModifier
                    .size(22.dp)
                    .cornerRadius(6.dp)
                    .clickable(actionRunCallback<RefreshWidgetsAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = "更新",
                    colorFilter = ColorFilter.tint(mutedColor),
                    modifier = GlanceModifier.size(13.dp),
                )
            }
        }

        if (progressLines.isEmpty()) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = fallbackText?.value ?: snapshot.errorMessage ?: "更新待機中",
                style = TextStyle(color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        } else {
            progressLines.forEach { p ->
                val pace = PaceCalculator.calculate(
                    p.used,
                    p.limit,
                    p.resetsAtMs,
                    p.periodDurationMs,
                    nowMs,
                    settings.workdaysOnlyWeeklyPace,
                )
                val pctStr = UsageDisplayFormatter.formatPercentShort(p.used, p.limit, settings.displayMode)
                val displayedFrac = if (settings.displayMode == UsageDisplayMode.LEFT) {
                    p.remainingFraction.toFloat()
                } else {
                    p.usedFraction.toFloat()
                }
                val (threshold1Pct, threshold2Pct) = settings.effectiveAlertThresholds(snapshot.accountId)
                val bmp = WidgetBitmapRenderer.renderProgressBarBitmap(
                    displayedFraction = displayedFrac,
                    fillColorArgb = fillArgb,
                    trackColorArgb = trackArgb,
                    markerColorArgb = markerArgb,
                    tickColorArgb = tickArgb,
                    paceElapsedFraction = if (settings.showPaceMarkers) pace?.elapsedFraction?.toFloat() else null,
                    isLeftMode = settings.displayMode == UsageDisplayMode.LEFT,
                    threshold1Pct = threshold1Pct,
                    threshold2Pct = threshold2Pct,
                )
                Spacer(GlanceModifier.height(4.dp))
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isTall) p.label else "",
                        style = TextStyle(color = mutedColor, fontSize = 10.sp),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        text = if (settings.displayMode == UsageDisplayMode.LEFT) "残り $pctStr" else "$pctStr 使用",
                        style = TextStyle(
                            color = ColorProvider(WidgetPalette.paceColor(pace?.tier, isDark)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Spacer(GlanceModifier.height(2.dp))
                Image(
                    provider = ImageProvider(bmp),
                    contentDescription = p.label,
                    modifier = GlanceModifier.fillMaxWidth().height(8.dp),
                )
                val reset = UsageDisplayFormatter.formatResetTime(p.resetsAtMs, nowMs, settings.resetTimerMode, settings.clock24h)
                if (reset != null && (isTall || progressLines.size == 1)) {
                    Text(
                        text = reset,
                        style = TextStyle(color = mutedColor, fontSize = 9.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class UsageSingleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageSingleWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val app = context.applicationContext as? OpenTokenUsageApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            app.container.repository.removeWidgetBindings(appWidgetIds)
        }
    }
}
