package com.opentokenusage.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import com.opentokenusage.android.domain.model.AppThemeMode
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.ui.theme.ProviderVisuals
import androidx.glance.color.ColorProvider as DayNightColorProvider

class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as? OpenTokenUsageApp ?: return
        app.container.repository.initialize()
        app.container.repository.refreshAll(isManual = true)
    }
}

internal object WidgetPalette {
    fun bg(mode: AppThemeMode) = when (mode) {
        AppThemeMode.OLED_DARK -> ColorProvider(Color(0xFF000000))
        AppThemeMode.LIGHT -> ColorProvider(Color(0xFFFAFAFA))
        AppThemeMode.SYSTEM -> DayNightColorProvider(Color(0xFFFAFAFA), Color(0xFF000000))
    }

    fun surface(mode: AppThemeMode) = when (mode) {
        AppThemeMode.OLED_DARK -> ColorProvider(Color(0xFF111111))
        AppThemeMode.LIGHT -> ColorProvider(Color(0xFFFFFFFF))
        AppThemeMode.SYSTEM -> DayNightColorProvider(Color(0xFFFFFFFF), Color(0xFF111111))
    }

    fun textPrimary(mode: AppThemeMode) = when (mode) {
        AppThemeMode.OLED_DARK -> ColorProvider(Color(0xFFFFFFFF))
        AppThemeMode.LIGHT -> ColorProvider(Color(0xFF1A1A1A))
        AppThemeMode.SYSTEM -> DayNightColorProvider(Color(0xFF1A1A1A), Color(0xFFFFFFFF))
    }

    fun textMuted(mode: AppThemeMode) = when (mode) {
        AppThemeMode.OLED_DARK -> ColorProvider(Color(0xFFA1A1AA))
        AppThemeMode.LIGHT -> ColorProvider(Color(0xFF5D5D5D))
        AppThemeMode.SYSTEM -> DayNightColorProvider(Color(0xFF5D5D5D), Color(0xFFA1A1AA))
    }

    fun isDarkStatic(context: Context, mode: AppThemeMode): Boolean = when (mode) {
        AppThemeMode.OLED_DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> {
            val mask = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun paceColor(tier: PaceTier?, isDark: Boolean): Color = when (tier) {
        PaceTier.AHEAD -> if (isDark) Color(0xFF6CCB5F) else Color(0xFF0F7B0F)
        PaceTier.ON_TRACK -> if (isDark) Color(0xFFFCE100) else Color(0xFF9D5D00)
        PaceTier.BEHIND -> if (isDark) Color(0xFFFF99A4) else Color(0xFFC42B1C)
        null -> if (isDark) Color(0xFFA1A1AA) else Color(0xFF5D5D5D)
    }
}

class UsageOverviewWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? OpenTokenUsageApp
        val repo = app?.container?.repository
        repo?.initialize()

        val settings = repo?.settings?.value ?: AppSettings()
        val snapshots = repo?.orderedDisplaySnapshots().orEmpty()
        val lastSyncMs = repo?.lastSyncMs?.value
        val fxRates = repo?.fxRates?.value.orEmpty()
        val nowMs = System.currentTimeMillis()
        val isDark = WidgetPalette.isDarkStatic(context, settings.themeMode)

        provideContent {
            OverviewWidgetContent(
                context = context,
                settings = settings,
                snapshots = snapshots,
                lastSyncMs = lastSyncMs,
                fxRates = fxRates,
                nowMs = nowMs,
                isDark = isDark,
            )
        }
    }
}

@Composable
private fun OverviewWidgetContent(
    context: Context,
    settings: AppSettings,
    snapshots: List<ProviderSnapshot>,
    lastSyncMs: Long?,
    fxRates: Map<String, Double>,
    nowMs: Long,
    isDark: Boolean,
) {
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val themeMode = settings.themeMode
    val primaryColor = WidgetPalette.textPrimary(themeMode)
    val mutedColor = WidgetPalette.textMuted(themeMode)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetPalette.bg(themeMode))
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        // Header Bar
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(openAppIntent)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "OpenTokenUsage",
                    style = TextStyle(color = primaryColor, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                if (lastSyncMs != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = UsageDisplayFormatter.formatTimeHm(lastSyncMs, settings.clock24h),
                        style = TextStyle(color = mutedColor, fontSize = 11.sp),
                    )
                }
            }
            Box(
                modifier = GlanceModifier
                    .size(28.dp)
                    .cornerRadius(8.dp)
                    .background(WidgetPalette.surface(themeMode))
                    .clickable(actionRunCallback<RefreshWidgetsAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = "更新",
                    colorFilter = ColorFilter.tint(primaryColor),
                    modifier = GlanceModifier.size(16.dp),
                )
            }
        }

        if (snapshots.isEmpty()) {
            // Strict empty state: never pre-populate placeholder or fake metrics.
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetPalette.surface(themeMode))
                    .cornerRadius(10.dp)
                    .clickable(actionStartActivity(openAppIntent))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "プロバイダー未接続",
                    style = TextStyle(color = primaryColor, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = "タップして API キーまたは PC同期を追加",
                    style = TextStyle(color = mutedColor, fontSize = 11.sp),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(snapshots, itemId = { it.accountId.hashCode().toLong() }) { snap ->
                    WidgetProviderRow(
                        context = context,
                        snap = snap,
                        settings = settings,
                        fxRates = fxRates,
                        nowMs = nowMs,
                        isDark = isDark,
                        openAppIntent = openAppIntent,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetProviderRow(
    context: Context,
    snap: ProviderSnapshot,
    settings: AppSettings,
    fxRates: Map<String, Double>,
    nowMs: Long,
    isDark: Boolean,
    openAppIntent: Intent,
) {
    val themeMode = settings.themeMode
    val primaryColor = WidgetPalette.textPrimary(themeMode)
    val mutedColor = WidgetPalette.textMuted(themeMode)
    val iconColor = ProviderVisuals.iconTint(snap.providerId, isDark)
    val fillArgb = ProviderVisuals.barColor(snap.providerId, isDark).toArgb()
    val trackArgb = if (isDark) Color(0x28FFFFFF).toArgb() else Color(0x1C000000).toArgb()
    val markerArgb = if (isDark) Color(0xFFFFFFFF).toArgb() else Color(0xFF1A1A1A).toArgb()
    val tickArgb = if (isDark) Color(0x55FFFFFF).toArgb() else Color(0x44000000).toArgb()

    val primaryProgress = snap.primaryProgressLines(maxCount = 1).firstOrNull()
    val firstText = snap.overviewLines().firstOrNull { it is MetricLine.Text } as? MetricLine.Text

    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(WidgetPalette.surface(themeMode))
                .cornerRadius(10.dp)
                .clickable(actionStartActivity(openAppIntent))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(ProviderVisuals.iconRes(snap.providerId)),
                    contentDescription = snap.displayName,
                    colorFilter = ColorFilter.tint(ColorProvider(iconColor)),
                    modifier = GlanceModifier.size(16.dp),
                )
                Spacer(GlanceModifier.width(7.dp))
                Text(
                    text = if (snap.accountLabel.isNotBlank()) "${snap.displayName} (${snap.accountLabel})" else snap.displayName,
                    style = TextStyle(color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (primaryProgress != null) {
                    val pace = PaceCalculator.calculate(
                        primaryProgress.used,
                        primaryProgress.limit,
                        primaryProgress.resetsAtMs,
                        primaryProgress.periodDurationMs,
                        nowMs,
                        settings.workdaysOnlyWeeklyPace,
                    )
                    val valueText = UsageDisplayFormatter.formatPercentShort(
                        primaryProgress.used,
                        primaryProgress.limit,
                        settings.displayMode,
                    )
                    Text(
                        text = if (settings.displayMode == UsageDisplayMode.LEFT) "残り $valueText" else "$valueText 使用",
                        style = TextStyle(
                            color = ColorProvider(WidgetPalette.paceColor(pace?.tier, isDark)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                } else if (firstText != null) {
                    Text(
                        text = firstText.value,
                        style = TextStyle(color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    )
                } else if (!snap.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "エラー",
                        style = TextStyle(color = ColorProvider(WidgetPalette.paceColor(PaceTier.BEHIND, isDark)), fontSize = 11.sp),
                    )
                }
            }

            if (primaryProgress != null) {
                val pace = PaceCalculator.calculate(
                    primaryProgress.used,
                    primaryProgress.limit,
                    primaryProgress.resetsAtMs,
                    primaryProgress.periodDurationMs,
                    nowMs,
                    settings.workdaysOnlyWeeklyPace,
                )
                val displayedFrac = if (settings.displayMode == UsageDisplayMode.LEFT) {
                    primaryProgress.remainingFraction.toFloat()
                } else {
                    primaryProgress.usedFraction.toFloat()
                }
                val bmp = WidgetBitmapRenderer.renderProgressBarBitmap(
                    displayedFraction = displayedFrac,
                    fillColorArgb = fillArgb,
                    trackColorArgb = trackArgb,
                    markerColorArgb = markerArgb,
                    tickColorArgb = tickArgb,
                    paceElapsedFraction = if (settings.showPaceMarkers) pace?.elapsedFraction?.toFloat() else null,
                    isLeftMode = settings.displayMode == UsageDisplayMode.LEFT,
                    threshold1Pct = settings.alertThreshold1,
                    threshold2Pct = settings.alertThreshold2,
                )
                Spacer(GlanceModifier.height(4.dp))
                Image(
                    provider = ImageProvider(bmp),
                    contentDescription = primaryProgress.label,
                    modifier = GlanceModifier.fillMaxWidth().height(9.dp),
                )
                val resetText = UsageDisplayFormatter.formatResetTime(
                    primaryProgress.resetsAtMs,
                    nowMs,
                    settings.resetTimerMode,
                    settings.clock24h,
                )
                if (resetText != null || primaryProgress.label.isNotBlank()) {
                    Spacer(GlanceModifier.height(2.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(
                            text = primaryProgress.label,
                            style = TextStyle(color = mutedColor, fontSize = 10.sp),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        if (resetText != null) {
                            Text(
                                text = resetText,
                                style = TextStyle(color = mutedColor, fontSize = 10.sp),
                            )
                        }
                    }
                }
            } else if (!snap.errorMessage.isNullOrBlank()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = snap.errorMessage,
                    style = TextStyle(color = mutedColor, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

class UsageOverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageOverviewWidget()
}
