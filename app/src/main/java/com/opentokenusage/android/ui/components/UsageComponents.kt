package com.opentokenusage.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentokenusage.android.domain.logic.PaceCalculator
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.FetchFailureCause
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.domain.model.diagnoseFetchFailure
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.ui.theme.ProviderVisuals

/**
 * Renders an OpenTokenUsage-faithful horizontal progress bar with uniform-pace
 * vertical marker needle and alert threshold ticks.
 */
@Composable
fun UsageProgressBar(
    usedFraction: Float,
    displayedFraction: Float,
    fillColor: Color,
    paceElapsedFraction: Float?,
    threshold1Pct: Int,
    threshold2Pct: Int,
    displayMode: UsageDisplayMode,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOtuColors.current
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp),
    ) {
        val trackHeight = 7.dp.toPx()
        val topY = (size.height - trackHeight) / 2f
        val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)

        // Background track
        drawRoundRect(
            color = colors.track,
            topLeft = Offset(0f, topY),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
        )

        // Alert threshold notches inside track (flipped in LEFT mode to match bar fill position)
        listOf(threshold1Pct, threshold2Pct)
            .filter { it in 1..99 }
            .forEach { pct ->
                val rawPos = pct / 100f
                val pos = if (displayMode == UsageDisplayMode.LEFT) 1f - rawPos else rawPos
                val x = (size.width * pos).coerceIn(2f, size.width - 2f)
                drawLine(
                    color = colors.textMuted.copy(alpha = 0.5f),
                    start = Offset(x, topY + 1f),
                    end = Offset(x, topY + trackHeight - 1f),
                    strokeWidth = 1.dp.toPx(),
                )
            }

        // Filled bar
        val clampedFill = displayedFraction.coerceIn(0f, 1f)
        if (clampedFill > 0.005f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, topY),
                size = Size((size.width * clampedFill).coerceAtLeast(trackHeight), trackHeight),
                cornerRadius = corner,
            )
        }

        // Uniform-pace marker needle (extends above & below track)
        if (paceElapsedFraction != null && paceElapsedFraction in 0.02f..0.99f) {
            val markerFraction = if (displayMode == UsageDisplayMode.LEFT) {
                1f - paceElapsedFraction
            } else {
                paceElapsedFraction
            }
            val x = (size.width * markerFraction).coerceIn(2f, size.width - 2f)
            drawLine(
                color = colors.textPrimary.copy(alpha = 0.85f),
                start = Offset(x, 1.dp.toPx()),
                end = Offset(x, size.height - 1.dp.toPx()),
                strokeWidth = 1.8.dp.toPx(),
            )
        }
    }
}

@Composable
fun UsageSparklineChart(
    values: List<Double>,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOtuColors.current
    if (values.isEmpty()) return
    val maxVal = (values.maxOrNull() ?: 100.0).coerceAtLeast(1.0)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        val count = values.size
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
        values.forEachIndexed { idx, v ->
            val hFraction = (v / maxVal).toFloat().coerceIn(0f, 1f)
            val barHeight = (size.height * hFraction).coerceAtLeast(2.dp.toPx())
            val x = idx * (barWidth + gap)
            drawRoundRect(
                color = if (hFraction > 0.85f) colors.paceRed else barColor.copy(alpha = 0.85f),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

@Composable
fun ServiceOutageBadge(
    modifier: Modifier = Modifier,
) {
    val colors = LocalOtuColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.warningBg)
            .border(1.dp, colors.warningBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = colors.paceRed,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = "障害発生中",
            color = colors.paceRed,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun FetchErrorBanner(
    snapshot: ProviderSnapshot,
    modifier: Modifier = Modifier,
) {
    val errorMessage = snapshot.errorMessage?.takeIf { it.isNotBlank() } ?: return
    val colors = LocalOtuColors.current
    val diagnosis = diagnoseFetchFailure(
        errorMessage = errorMessage,
        serviceStatus = snapshot.serviceStatus,
        isAuthError = snapshot.errorIsAuth,
        statusCode = snapshot.errorStatusCode,
    )
    val accentColor = when (diagnosis?.cause) {
        FetchFailureCause.SERVICE_OUTAGE -> colors.paceYellow
        else -> colors.paceRed
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(colors.warningBg)
            .border(1.dp, colors.warningBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(15.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (diagnosis != null) {
                Text(
                    text = diagnosis.summaryJa,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 15.sp,
                )
                if (!diagnosis.serviceDescription.isNullOrBlank()) {
                    Text(
                        text = diagnosis.serviceDescription,
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
            Text(
                text = if (snapshot.isStale) "前回のデータ表示中: $errorMessage" else errorMessage,
                color = colors.textPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
fun ProviderCard(
    snapshot: ProviderSnapshot,
    settings: AppSettings,
    cachedRates: Map<String, Double>,
    nowMs: Long,
    onCardClick: () -> Unit,
    onToggleResetMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOtuColors.current
    val tint = ProviderVisuals.iconTint(snapshot.providerId, colors.isDark)
    val barColor = ProviderVisuals.barColor(snapshot.providerId, colors.isDark)
    val tnumStyle = TextStyle(fontFeatureSettings = "tnum")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.elevatedSurface)
                        .border(1.dp, colors.border, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(ProviderVisuals.iconRes(snapshot.providerId)),
                        contentDescription = snapshot.displayName,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = snapshot.displayName,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (snapshot.accountLabel.isNotBlank()) {
                            Text(
                                text = snapshot.accountLabel,
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!snapshot.plan.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.elevatedSurface)
                                    .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = snapshot.plan,
                                    color = colors.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (snapshot.serviceStatus?.isOutage == true) {
                    Spacer(Modifier.width(6.dp))
                    ServiceOutageBadge()
                    Spacer(Modifier.width(4.dp))
                }

                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "詳細",
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Error / Stale Banner
            if (!snapshot.errorMessage.isNullOrBlank()) {
                FetchErrorBanner(snapshot = snapshot)
            }

            // Metrics
            val overview = snapshot.overviewLines().take(settings.overviewMaxLines)
            if (overview.isEmpty() && snapshot.errorMessage.isNullOrBlank()) {
                Text(
                    text = if (snapshot.fetchedAtMs == 0L) "更新待機中..." else "表示できるメトリクスがありません",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    for (line in overview) {
                        when (line) {
                            is MetricLine.Progress -> {
                                val pace = PaceCalculator.calculate(
                                    used = line.used,
                                    limit = line.limit,
                                    resetsAtMs = line.resetsAtMs,
                                    periodDurationMs = line.periodDurationMs,
                                    nowMs = nowMs,
                                    workdays = settings.workdaysOnlyWeeklyPace,
                                )
                                val displayedFrac = if (settings.displayMode == UsageDisplayMode.LEFT) {
                                    line.remainingFraction.toFloat()
                                } else {
                                    line.usedFraction.toFloat()
                                }
                                val valueStr = UsageDisplayFormatter.formatMetricValue(
                                    line.used,
                                    line.limit,
                                    line.format,
                                    settings,
                                    cachedRates,
                                )
                                val resetStr = UsageDisplayFormatter.formatResetTime(
                                    line.resetsAtMs,
                                    nowMs,
                                    settings.resetTimerMode,
                                    settings.clock24h,
                                )
                                val paceDetail = if (pace?.tier != null) {
                                    UsageDisplayFormatter.formatPaceDetailJa(
                                        pace = pace,
                                        limit = line.limit,
                                        format = line.format,
                                        settings = settings,
                                        cachedRates = cachedRates,
                                    )
                                } else {
                                    null
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (pace != null) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.paceColor(pace.tier)),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = line.label,
                                            color = colors.textSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = valueStr,
                                            style = tnumStyle,
                                            color = colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }

                                    val (threshold1Pct, threshold2Pct) = settings.effectiveAlertThresholds(snapshot.accountId)
                                    UsageProgressBar(
                                        usedFraction = line.usedFraction.toFloat(),
                                        displayedFraction = displayedFrac,
                                        fillColor = barColor,
                                        paceElapsedFraction = if (settings.showPaceMarkers) {
                                            pace?.elapsedFraction?.toFloat()
                                        } else {
                                            null
                                        },
                                        threshold1Pct = threshold1Pct,
                                        threshold2Pct = threshold2Pct,
                                        displayMode = settings.displayMode,
                                    )

                                    if (resetStr != null || paceDetail != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = paceDetail ?: "",
                                                style = tnumStyle,
                                                color = if (pace != null) colors.paceColor(pace.tier) else colors.textMuted,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (resetStr != null) {
                                                Text(
                                                    text = resetStr,
                                                    style = tnumStyle,
                                                    color = colors.textMuted,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable(onClick = onToggleResetMode)
                                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is MetricLine.Text -> {
                                val valueColor = parseLineColorOrNull(line.colorHex) ?: colors.textPrimary
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = line.label,
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = line.value,
                                            style = tnumStyle,
                                            color = valueColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (!line.subtitle.isNullOrBlank()) {
                                            Text(
                                                text = line.subtitle,
                                                color = colors.textMuted,
                                                fontSize = 10.sp,
                                            )
                                        }
                                    }
                                }
                            }

                            is MetricLine.Badge -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = line.label,
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.elevatedSurface)
                                            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = line.text,
                                            color = colors.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }

                            is MetricLine.BarChart -> {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(line.label, color = colors.textSecondary, fontSize = 11.sp)
                                    UsageSparklineChart(values = line.values, barColor = barColor)
                                }
                            }

                            is MetricLine.ResetCredit -> {
                                val activeCount = line.activeCount(nowMs)
                                if (activeCount > 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(line.label, color = colors.textSecondary, fontSize = 12.sp)
                                        Text(
                                            text = "${activeCount} 回利用可能",
                                            style = tnumStyle,
                                            color = colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun parseLineColorOrNull(colorHex: String?): Color? {
    val trimmed = colorHex?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Color(android.graphics.Color.parseColor(trimmed)) }.getOrNull()
}
