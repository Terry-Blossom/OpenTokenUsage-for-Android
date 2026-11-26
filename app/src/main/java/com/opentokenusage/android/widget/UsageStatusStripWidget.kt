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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.OpenTokenUsageApp
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.AppThemeMode
import com.opentokenusage.android.ui.theme.ProviderVisuals
import com.opentokenusage.android.util.UsageStatusEntry
import com.opentokenusage.android.util.usageStatusEntries
import com.opentokenusage.android.util.usageStatusIconColor
import com.opentokenusage.android.util.usageStatusTextColor

/**
 * 3x1 home-screen mirror of the persistent notification strip: one cell per provider
 * with the provider icon and the primary / weekly percentages.
 */
class UsageStatusStripWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? OpenTokenUsageApp
        val repo = app?.container?.repository
        repo?.initialize()

        val settings = repo?.settings?.value ?: AppSettings()
        val entries = usageStatusEntries(repo?.orderedDisplaySnapshots().orEmpty())
        val isDark = WidgetPalette.isDarkStatic(context, settings.themeMode)

        provideContent {
            StatusStripContent(
                context = context,
                entries = entries,
                settings = settings,
                isDark = isDark,
            )
        }
    }
}

@Composable
private fun StatusStripContent(
    context: Context,
    entries: List<UsageStatusEntry>,
    settings: AppSettings,
    isDark: Boolean,
) {
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val themeMode = settings.themeMode
    val mutedColor = WidgetPalette.textMuted(themeMode)
    val backgroundArgb = statusStripBackground(themeMode, isDark)
    val baseTextColor = if (isDark) Color(0xFFFFFFFF).toArgb() else Color(0xFF1A1A1A).toArgb()

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetPalette.bg(themeMode))
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(openAppIntent))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entries.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "プロバイダー未接続",
                    style = TextStyle(color = mutedColor, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        } else {
            entries.forEach { entry ->
                Row(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        provider = ImageProvider(ProviderVisuals.iconRes(entry.snapshot.providerId)),
                        contentDescription = entry.snapshot.displayName,
                        colorFilter = ColorFilter.tint(
                            ColorProvider(Color(usageStatusIconColor(entry.snapshot.providerId, isDark))),
                        ),
                        modifier = GlanceModifier.size(22.dp),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Column {
                        entry.lines.forEachIndexed { lineIndex, line ->
                            Text(
                                text = UsageDisplayFormatter.formatPercentShort(
                                    line.used,
                                    line.limit,
                                    settings.displayMode,
                                ),
                                style = TextStyle(
                                    color = ColorProvider(
                                        Color(usageStatusTextColor(line.usedFraction, baseTextColor, backgroundArgb)),
                                    ),
                                    fontSize = if (lineIndex == 0) 14.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun statusStripBackground(mode: AppThemeMode, isDark: Boolean): Int = when (mode) {
    AppThemeMode.OLED_DARK -> Color(0xFF000000).toArgb()
    AppThemeMode.LIGHT -> Color(0xFFFAFAFA).toArgb()
    AppThemeMode.SYSTEM -> if (isDark) Color(0xFF000000).toArgb() else Color(0xFFFAFAFA).toArgb()
}

class UsageStatusStripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageStatusStripWidget()
}
