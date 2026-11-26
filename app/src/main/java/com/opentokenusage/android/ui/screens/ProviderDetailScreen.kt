package com.opentokenusage.android.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentokenusage.android.domain.logic.PaceCalculator
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.domain.model.UsageHistoryEntry
import com.opentokenusage.android.ui.components.UsageProgressBar
import com.opentokenusage.android.ui.components.UsageSparklineChart
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.ui.theme.ProviderVisuals
import com.opentokenusage.android.widget.UsageSingleWidgetReceiver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    snapshot: ProviderSnapshot?,
    history: List<UsageHistoryEntry>,
    settings: AppSettings,
    fxRates: Map<String, Double>,
    isOwnedAccount: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleResetMode: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalOtuColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (snapshot == null) {
        LaunchedEffectOnMissing(onBack)
        return
    }

    val provider = ProviderId.fromId(snapshot.providerId)
    val tint = ProviderVisuals.iconTint(snapshot.providerId, colors.isDark)
    val barColor = ProviderVisuals.barColor(snapshot.providerId, colors.isDark)
    val nowMs = System.currentTimeMillis()
    val tnum = TextStyle(fontFeatureSettings = "tnum")

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("${snapshot.displayName} を削除") },
            text = { Text("保存されているAPIキー・トークン・キャッシュデータを端末から削除します。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                        onBack()
                    },
                ) {
                    Text("削除", color = colors.paceRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            },
        )
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            painter = painterResource(ProviderVisuals.iconRes(snapshot.providerId)),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(snapshot.displayName, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = colors.textPrimary)
                    }
                },
                actions = {
                    if (isOwnedAccount) {
                        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = "更新", tint = colors.textPrimary)
                            }
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = "編集", tint = colors.textPrimary)
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "削除", tint = colors.paceRed)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!snapshot.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.warningBg,
                    border = BorderStroke(1.dp, colors.warningBorder),
                ) {
                    Text(
                        text = snapshot.errorMessage,
                        color = colors.paceRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = colors.card,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = snapshot.plan ?: "プラン情報なし",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (snapshot.fetchedAtMs > 0L) {
                            Text(
                                text = "取得: ${UsageDisplayFormatter.formatTimeHm(snapshot.fetchedAtMs, settings.clock24h)}",
                                style = tnum,
                                color = colors.textMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    snapshot.lines.forEach { line ->
                        when (line) {
                            is MetricLine.Progress -> {
                                val pace = PaceCalculator.calculate(
                                    line.used,
                                    line.limit,
                                    line.resetsAtMs,
                                    line.periodDurationMs,
                                    nowMs,
                                    settings.workdaysOnlyWeeklyPace,
                                )
                                val displayedFrac = if (settings.displayMode == UsageDisplayMode.LEFT) {
                                    line.remainingFraction.toFloat()
                                } else {
                                    line.usedFraction.toFloat()
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                            color = colors.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = UsageDisplayFormatter.formatMetricValue(
                                                line.used,
                                                line.limit,
                                                line.format,
                                                settings,
                                                fxRates,
                                            ),
                                            style = tnum,
                                            color = colors.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    UsageProgressBar(
                                        usedFraction = line.usedFraction.toFloat(),
                                        displayedFraction = displayedFrac,
                                        fillColor = barColor,
                                        paceElapsedFraction = if (settings.showPaceMarkers) pace?.elapsedFraction?.toFloat() else null,
                                        threshold1Pct = settings.alertThreshold1,
                                        threshold2Pct = settings.alertThreshold2,
                                        displayMode = settings.displayMode,
                                    )
                                    val paceText = UsageDisplayFormatter.formatPaceDetailJa(pace, line.limit, line.format, settings, fxRates)
                                    val resetText = UsageDisplayFormatter.formatResetTime(line.resetsAtMs, nowMs, settings.resetTimerMode, settings.clock24h)
                                    if (paceText != null || resetText != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = paceText ?: "",
                                                style = tnum,
                                                color = if (pace != null) colors.paceColor(pace.tier) else colors.textMuted,
                                                fontSize = 11.sp,
                                            )
                                            if (resetText != null) {
                                                Text(
                                                    text = resetText,
                                                    style = tnum,
                                                    color = colors.textMuted,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.clickable(onClick = onToggleResetMode),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is MetricLine.Text -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(line.label, color = colors.textSecondary, fontSize = 13.sp)
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(line.value, style = tnum, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (!line.subtitle.isNullOrBlank()) {
                                        Text(line.subtitle, color = colors.textMuted, fontSize = 11.sp)
                                    }
                                }
                            }
                            is MetricLine.Badge -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(line.label, color = colors.textSecondary, fontSize = 13.sp)
                                Text(
                                    text = line.text,
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.elevatedSurface)
                                        .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            is MetricLine.BarChart -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(line.label, color = colors.textSecondary, fontSize = 12.sp)
                                UsageSparklineChart(values = line.values, barColor = barColor)
                            }
                            is MetricLine.ResetCredit -> {
                                val activeItems = line.activeItems(nowMs)
                                val activeCount = line.activeCount(nowMs)
                                if (activeCount > 0) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(line.label, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text("${activeCount} 回利用可能", style = tnum, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                        activeItems.forEach { item ->
                                            val itemLabel = if (item.kind != null) {
                                                "${UsageDisplayFormatter.resetCreditKindLabelJa(item.kind)} ×${item.count}"
                                            } else {
                                                "${item.count} 回"
                                            }
                                            val remainingMs = item.expiresAtMs?.let { it - nowMs }
                                            val expiryColor = when {
                                                remainingMs == null -> colors.textMuted
                                                remainingMs <= 24L * 3600_000L -> colors.paceRed
                                                remainingMs <= 7L * 24L * 3600_000L -> colors.paceYellow
                                                else -> colors.textMuted
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(itemLabel, color = colors.textSecondary, fontSize = 12.sp)
                                                    Text(
                                                        text = UsageDisplayFormatter.formatResetCreditExpiryJa(
                                                            item.expiresAtMs,
                                                            nowMs,
                                                            settings.clock24h,
                                                        ),
                                                        style = tnum,
                                                        color = expiryColor,
                                                        fontSize = 11.sp,
                                                    )
                                                }
                                                UsageDisplayFormatter.resetCreditKindDescriptionJa(item.kind)?.let { desc ->
                                                    Text(desc, color = colors.textMuted, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                } else if (line.amount > 0.0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(line.label, color = colors.textSecondary, fontSize = 12.sp)
                                        Text("${line.currencySymbol}${line.amount}", color = colors.textPrimary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (history.size >= 2) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.card,
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("取得履歴の推移（使用率%）", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        val firstSeriesLabel = history.first().lineLabel
                        val series = history.filter { it.lineLabel == firstSeriesLabel }.takeLast(24).map { it.usedPercent }
                        UsageSparklineChart(values = series, barColor = barColor)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.border),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val awm = context.getSystemService(AppWidgetManager::class.java)
                            if (awm != null && awm.isRequestPinAppWidgetSupported) {
                                awm.requestPinAppWidget(
                                    ComponentName(context, UsageSingleWidgetReceiver::class.java),
                                    null,
                                    null,
                                )
                            }
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("個別ウィジェット配置", fontSize = 12.sp)
                }

                if (provider != null && provider.keyHelpUrl.isNotBlank()) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, colors.border),
                        onClick = { runCatching { uriHandler.openUri(provider.keyHelpUrl) } },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("公式サイト", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedEffectOnMissing(onBack: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
}
