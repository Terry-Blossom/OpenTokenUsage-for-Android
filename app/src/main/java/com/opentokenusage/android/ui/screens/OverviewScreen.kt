package com.opentokenusage.android.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.ResetTimerMode
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.ui.components.ProviderCard
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.widget.UsageOverviewWidgetReceiver
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    accounts: List<ConfiguredAccount>,
    displaySnapshots: List<ProviderSnapshot>,
    settings: AppSettings,
    fxRates: Map<String, Double>,
    isRefreshing: Boolean,
    lastSyncMs: Long?,
    onRefreshAll: () -> Unit,
    onAddProvider: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onToggleResetMode: () -> Unit,
) {
    val colors = LocalOtuColors.current
    val context = LocalContext.current
    val nowMsState = remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMsState.longValue = System.currentTimeMillis()
        }
    }
    val nowMs = nowMsState.longValue

    Scaffold(
        containerColor = colors.background,
        topBar = {
            Surface(
                color = colors.background,
                border = BorderStroke(0.dp, Color.Transparent),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "OpenTokenUsage",
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (accounts.isNotEmpty()) {
                        IconButton(onClick = onRefreshAll, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "すべて更新",
                                    tint = colors.textPrimary,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onAddProvider(null) }) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "プロバイダーを追加",
                            tint = colors.textPrimary,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "設定",
                            tint = colors.textPrimary,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (accounts.isNotEmpty()) {
                Surface(
                    color = colors.card,
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactChipButton(
                                label = if (settings.displayMode == UsageDisplayMode.LEFT) "表示: 残り" else "表示: 使用済み",
                                onClick = onToggleDisplayMode,
                            )
                            CompactChipButton(
                                label = if (settings.resetTimerMode == ResetTimerMode.RELATIVE) "時刻: 残り時間" else "時刻: 日時",
                                onClick = onToggleResetMode,
                            )
                        }
                        Text(
                            text = lastSyncMs?.let {
                                "更新 ${UsageDisplayFormatter.formatTimeHm(it, settings.clock24h)}"
                            } ?: "未同期",
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            color = colors.textMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (accounts.isEmpty()) {
                EmptyDashboardView(
                    onAddProvider = { onAddProvider(null) },
                    onAddDesktopSync = { onAddProvider(ProviderId.DESKTOP_SYNC.id) },
                    onPinOverviewWidget = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val awm = context.getSystemService(AppWidgetManager::class.java)
                            if (awm != null && awm.isRequestPinAppWidgetSupported) {
                                awm.requestPinAppWidget(
                                    ComponentName(context, UsageOverviewWidgetReceiver::class.java),
                                    null,
                                    null,
                                )
                            }
                        }
                    },
                )
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefreshAll,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(displaySnapshots, key = { it.accountId }) { snap ->
                            ProviderCard(
                                snapshot = snap,
                                settings = settings,
                                cachedRates = fxRates,
                                nowMs = nowMs,
                                onCardClick = { onOpenDetail(snap.accountId) },
                                onToggleResetMode = onToggleResetMode,
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactChipButton(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalOtuColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.elevatedSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyDashboardView(
    onAddProvider: () -> Unit,
    onAddDesktopSync: () -> Unit,
    onPinOverviewWidget: () -> Unit,
) {
    val colors = LocalOtuColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colors.card,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "接続済みのプロバイダーはありません",
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "APIキー、CLI認証JSON、またはデスクトップ版の接続先を追加すると、使用率とホーム画面ウィジェットに実データが表示されます。",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAddProvider,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("プロバイダーを追加", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onAddDesktopSync,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("デスクトップデータを取り込む", color = colors.textPrimary)
                }
                OutlinedButton(
                    onClick = onPinOverviewWidget,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Icon(
                        Icons.Outlined.Widgets,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("ホーム画面にウィジェットを追加", color = colors.textSecondary)
                }
            }
        }
    }
}
