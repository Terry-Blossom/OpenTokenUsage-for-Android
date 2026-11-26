package com.opentokenusage.android.widget

import android.appwidget.AppWidgetManager
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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.OpenTokenUsageApp
import com.opentokenusage.android.R
import com.opentokenusage.android.domain.logic.PaceTier
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.AppThemeMode
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.ui.theme.ProviderVisuals
import com.opentokenusage.android.util.selectStatusStripLines
import com.opentokenusage.android.util.usageStatusIconColor
import com.opentokenusage.android.util.usageStatusTextColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class MiniWidgetDisplay(
    val providerId: String?,
    val displayName: String?,
    val valueText: String,
    val usedFraction: Double?,
    val needsConfiguration: Boolean,
    val hasError: Boolean,
)

internal fun buildMiniWidgetDisplay(
    snapshot: ProviderSnapshot?,
    boundAccountId: String?,
    displayMode: UsageDisplayMode,
): MiniWidgetDisplay {
    if (snapshot == null) {
        val unbound = boundAccountId == null
        return MiniWidgetDisplay(
            providerId = null,
            displayName = null,
            valueText = if (unbound) "設定" else "--",
            usedFraction = null,
            needsConfiguration = unbound,
            hasError = false,
        )
    }
    val primary = selectStatusStripLines(snapshot)?.primary
        ?: snapshot.primaryProgressLines(maxCount = 1).firstOrNull()
    if (primary != null) {
        return MiniWidgetDisplay(
            providerId = snapshot.providerId,
            displayName = snapshot.displayName,
            valueText = UsageDisplayFormatter.formatPercentShort(primary.used, primary.limit, displayMode),
            usedFraction = primary.usedFraction,
            needsConfiguration = false,
            hasError = false,
        )
    }
    val hasError = !snapshot.errorMessage.isNullOrBlank()
    return MiniWidgetDisplay(
        providerId = snapshot.providerId,
        displayName = snapshot.displayName,
        valueText = if (hasError) "!" else "--",
        usedFraction = null,
        needsConfiguration = false,
        hasError = hasError,
    )
}

/**
 * 1x1 home-screen Glance widget showing a single configured account's provider logo
 * and primary usage percentage.
 */
class UsageMiniWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? OpenTokenUsageApp
        val repo = app?.container?.repository
        repo?.initialize()

        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrNull()

        val settings = repo?.settings?.value ?: AppSettings()
        val boundAccountId = appWidgetId?.let { repo?.getWidgetBinding(it) }
        val selectedSnapshot = boundAccountId?.let { targetId ->
            repo?.orderedDisplaySnapshots().orEmpty().firstOrNull { it.accountId == targetId }
        }
        val display = buildMiniWidgetDisplay(
            snapshot = selectedSnapshot,
            boundAccountId = boundAccountId,
            displayMode = settings.displayMode,
        )
        val isDark = WidgetPalette.isDarkStatic(context, settings.themeMode)

        provideContent {
            MiniWidgetContent(
                context = context,
                appWidgetId = appWidgetId,
                display = display,
                settings = settings,
                isDark = isDark,
            )
        }
    }
}

@Composable
private fun MiniWidgetContent(
    context: Context,
    appWidgetId: Int?,
    display: MiniWidgetDisplay,
    settings: AppSettings,
    isDark: Boolean,
) {
    val themeMode = settings.themeMode
    val mutedColor = WidgetPalette.textMuted(themeMode)
    val baseTextColor = if (isDark) Color(0xFFFFFFFF).toArgb() else Color(0xFF1A1A1A).toArgb()
    val backgroundArgb = miniWidgetBackground(themeMode, isDark)

    val configIntent = Intent(context, WidgetConfigureActivity::class.java).apply {
        if (appWidgetId != null) putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val clickIntent = if (display.needsConfiguration) configIntent else openAppIntent

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetPalette.bg(themeMode))
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(clickIntent))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val providerId = display.providerId
        if (providerId != null) {
            Image(
                provider = ImageProvider(ProviderVisuals.iconRes(providerId)),
                contentDescription = display.displayName,
                colorFilter = ColorFilter.tint(
                    ColorProvider(Color(usageStatusIconColor(providerId, isDark))),
                ),
                modifier = GlanceModifier.size(22.dp),
            )
            Spacer(GlanceModifier.height(3.dp))
            val valueColor = when {
                display.usedFraction != null -> ColorProvider(
                    Color(usageStatusTextColor(display.usedFraction, baseTextColor, backgroundArgb)),
                )
                display.hasError -> ColorProvider(WidgetPalette.paceColor(PaceTier.BEHIND, isDark))
                else -> mutedColor
            }
            Text(
                text = display.valueText,
                style = TextStyle(
                    color = valueColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_stat_usage),
                contentDescription = "OpenTokenUsage",
                colorFilter = ColorFilter.tint(mutedColor),
                modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.height(3.dp))
            Text(
                text = display.valueText,
                style = TextStyle(
                    color = mutedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

private fun miniWidgetBackground(mode: AppThemeMode, isDark: Boolean): Int = when (mode) {
    AppThemeMode.OLED_DARK -> Color(0xFF000000).toArgb()
    AppThemeMode.LIGHT -> Color(0xFFFAFAFA).toArgb()
    AppThemeMode.SYSTEM -> if (isDark) Color(0xFF000000).toArgb() else Color(0xFFFAFAFA).toArgb()
}

class UsageMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageMiniWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val app = context.applicationContext as? OpenTokenUsageApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            app.container.repository.removeWidgetBindings(appWidgetIds)
        }
    }
}
