package com.opentokenusage.android.util

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderSnapshot
import kotlin.math.abs

/** One provider cell in the status strip shared by the notification and the 1x3 widget. */
internal data class UsageStatusEntry(
    val snapshot: ProviderSnapshot,
    val lines: List<MetricLine.Progress>,
)

internal const val MAX_USAGE_STATUS_PROVIDERS = 3

/** Provider logos drawn with white glyphs need a tint that follows the strip background. */
private val MONOCHROME_GLYPH_PROVIDERS = setOf("grok", "opencode-go")

/**
 * Picks the same providers, in the same order, for both the persistent notification
 * and the home-screen status strip: one cell per provider, capped at three.
 */
internal fun usageStatusEntries(snapshots: List<ProviderSnapshot>): List<UsageStatusEntry> =
    snapshots.mapNotNull { snapshot ->
        statusNotificationLines(snapshot)
            .takeIf { it.isNotEmpty() }
            ?.let { UsageStatusEntry(snapshot, it) }
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

internal fun statusNotificationLines(snapshot: ProviderSnapshot): List<MetricLine.Progress> {
    val lines = snapshot.overviewLines().filterIsInstance<MetricLine.Progress>()
    val primary = lines.minByOrNull { it.primaryOrder ?: Int.MAX_VALUE } ?: return emptyList()
    val weekly = lines.firstOrNull { line ->
        line != primary && (line.period.equals("weekly", ignoreCase = true) ||
            (line.periodDurationMs != null && abs(line.periodDurationMs - 7L * 24L * 3600_000L) < 24L * 3600_000L))
    }
    return listOfNotNull(primary, weekly)
}
