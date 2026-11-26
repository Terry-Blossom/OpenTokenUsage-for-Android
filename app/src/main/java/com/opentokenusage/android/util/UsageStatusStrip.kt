package com.opentokenusage.android.util

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import java.util.Locale
import kotlin.math.abs

/** Selected progress lines for one provider cell in the status strip. */
internal data class StatusStripLines(
    val primary: MetricLine.Progress,
    val weekly: MetricLine.Progress? = null,
    val monthly: MetricLine.Progress? = null,
) {
    fun toList(): List<MetricLine.Progress> = listOfNotNull(primary, weekly, monthly)
}

/** One provider cell in the status strip shared by the notification and the 1x3 widget. */
internal data class UsageStatusEntry(
    val snapshot: ProviderSnapshot,
    val primary: MetricLine.Progress,
    val weekly: MetricLine.Progress? = null,
    val monthly: MetricLine.Progress? = null,
) {
    val lines: List<MetricLine.Progress>
        get() = listOfNotNull(primary, weekly, monthly)
}

internal data class StatusStripTextSizesSp(
    val primarySp: Float,
    val weeklySp: Float,
    val monthlySp: Float,
)

internal const val MAX_USAGE_STATUS_PROVIDERS = 3

private const val HOUR_MS = 3600_000L
private const val DAY_MS = 24L * HOUR_MS
private const val WEEK_MS = 7L * DAY_MS
private const val MONTHLY_MIN_MS = 28L * DAY_MS - 12L * HOUR_MS
private const val MONTHLY_MAX_MS = 31L * DAY_MS + 12L * HOUR_MS

/** Provider logos drawn with white glyphs need a tint that follows the strip background. */
private val MONOCHROME_GLYPH_PROVIDERS = setOf("grok", "opencode-go")

/**
 * Picks the same providers, in the same order, for both the persistent notification
 * and the home-screen status strip: one cell per provider, capped at three.
 */
internal fun usageStatusEntries(
    snapshots: List<ProviderSnapshot>,
    includeMonthly: Boolean = false,
): List<UsageStatusEntry> =
    snapshots.mapNotNull { snapshot ->
        selectStatusStripLines(snapshot, includeMonthly)?.let { selected ->
            UsageStatusEntry(
                snapshot = snapshot,
                primary = selected.primary,
                weekly = selected.weekly,
                monthly = selected.monthly,
            )
        }
    }
        .distinctBy { it.snapshot.providerId }
        .take(MAX_USAGE_STATUS_PROVIDERS)

internal fun usageStatusIconColor(providerId: String, darkBackground: Boolean): Int {
    val id = providerId.lowercase()
    if (id in MONOCHROME_GLYPH_PROVIDERS) {
        return if (darkBackground) Color.WHITE else Color.BLACK
    }
    return (ProviderId.fromId(id)?.brandColorRgb ?: 0xFF9CA3AF).toInt()
}

/**
 * Blends the usage scale from the strip's base text color through yellow and orange
 * to red, then lifts contrast against the strip background.
 */
internal fun usageStatusTextColor(
    usedFraction: Double,
    baseColor: Int,
    backgroundColor: Int,
): Int {
    val usedPercent = usedFraction.coerceIn(0.0, 1.0) * 100.0
    val stops = listOf(
        0.0 to baseColor,
        45.0 to Color.rgb(250, 204, 21),
        75.0 to Color.rgb(251, 146, 60),
        92.0 to Color.rgb(239, 68, 68),
    )
    for (index in 1 until stops.size) {
        val (end, endColor) = stops[index]
        if (usedPercent <= end) {
            val (start, startColor) = stops[index - 1]
            val fraction = ((usedPercent - start) / (end - start)).toFloat()
            return readableStatusColor(ColorUtils.blendARGB(startColor, endColor, fraction), backgroundColor)
        }
    }
    return readableStatusColor(stops.last().second, backgroundColor)
}

private fun readableStatusColor(color: Int, background: Int): Int {
    if (ColorUtils.calculateContrast(color, background) >= 3.0) return color
    val target = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
    for (step in 1..10) {
        val adjusted = ColorUtils.blendARGB(color, target, step / 10f)
        if (ColorUtils.calculateContrast(adjusted, background) >= 3.0) return adjusted
    }
    return target
}

internal fun isWeeklyStatusLine(line: MetricLine.Progress): Boolean =
    line.period.equals("weekly", ignoreCase = true) ||
        (line.periodDurationMs != null && abs(line.periodDurationMs - WEEK_MS) < DAY_MS)

internal fun isMonthlyStatusLine(line: MetricLine.Progress): Boolean {
    if (isWeeklyStatusLine(line)) return false
    if (line.period.equals("monthly", ignoreCase = true) || line.period.equals("month", ignoreCase = true)) {
        return true
    }
    val periodMs = line.periodDurationMs
    if (periodMs != null && periodMs in MONTHLY_MIN_MS..MONTHLY_MAX_MS) {
        return true
    }
    val lowerLabel = line.label.lowercase(Locale.ROOT)
    return "month" in lowerLabel || "月間" in line.label || "月次" in line.label
}

internal fun selectStatusStripLines(
    snapshot: ProviderSnapshot,
    includeMonthly: Boolean = false,
): StatusStripLines? {
    val overviewProgress = snapshot.overviewLines().filterIsInstance<MetricLine.Progress>()
    val primary = overviewProgress.minByOrNull { it.primaryOrder ?: Int.MAX_VALUE } ?: return null
    val weekly = overviewProgress.firstOrNull { line ->
        line != primary && isWeeklyStatusLine(line)
    }
    val monthly = if (includeMonthly && !isMonthlyStatusLine(primary)) {
        val candidates = overviewProgress + snapshot.lines
            .filter { it.scope == "detail" }
            .filterIsInstance<MetricLine.Progress>()
        candidates.firstOrNull { line ->
            line != primary && line != weekly && isMonthlyStatusLine(line)
        }
    } else {
        null
    }
    return StatusStripLines(primary = primary, weekly = weekly, monthly = monthly)
}

internal fun statusNotificationLines(
    snapshot: ProviderSnapshot,
    includeMonthly: Boolean = false,
): List<MetricLine.Progress> =
    selectStatusStripLines(snapshot, includeMonthly)?.toList().orEmpty()

internal fun formatMonthlyStatusText(
    line: MetricLine.Progress,
    displayMode: UsageDisplayMode,
): String = UsageDisplayFormatter.formatPercentShort(line.used, line.limit, displayMode)

internal fun formatStatusPreviewText(
    entries: List<UsageStatusEntry>,
    displayMode: UsageDisplayMode,
): String = entries.joinToString(" · ") { entry ->
    val parts = buildList {
        add(UsageDisplayFormatter.formatPercentShort(entry.primary.used, entry.primary.limit, displayMode))
        entry.weekly?.let { add(UsageDisplayFormatter.formatPercentShort(it.used, it.limit, displayMode)) }
        entry.monthly?.let { add(formatMonthlyStatusText(it, displayMode)) }
    }
    val outageMark = if (entry.snapshot.serviceStatus?.isOutage == true) "⚠障害 " else ""
    "${outageMark}${entry.snapshot.displayName} ${parts.joinToString(" / ")}"
}

/**
 * Keeps exact legacy 14sp / 12sp typography whenever the monthly line is absent,
 * and compacts slightly when three lines stack so the 48dp notification strip and
 * 1x3 home-screen widget never clip vertically.
 */
internal fun statusStripTextSizesSp(
    hasWeekly: Boolean,
    hasMonthly: Boolean,
): StatusStripTextSizesSp = when {
    !hasMonthly -> StatusStripTextSizesSp(primarySp = 14f, weeklySp = 12f, monthlySp = 10f)
    hasWeekly -> StatusStripTextSizesSp(primarySp = 13f, weeklySp = 11f, monthlySp = 9f)
    else -> StatusStripTextSizesSp(primarySp = 14f, weeklySp = 12f, monthlySp = 10f)
}
