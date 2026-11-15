package com.opentokenusage.android.ui.screens

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.opentokenusage.android.domain.logic.CurrencyFormatter
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.AppThemeMode
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import com.opentokenusage.android.domain.model.ResetTimerMode
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.ui.theme.ProviderVisuals
import com.opentokenusage.android.widget.UsageOverviewWidgetReceiver
import com.opentokenusage.android.widget.UsageSingleWidgetReceiver
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    accounts: List<ConfiguredAccount>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleAccountEnabled: (String, Boolean) -> Unit,
    onMoveAccount: (String, Int) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onEditAccount: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalOtuColors.current
    val context = LocalContext.current
    var widgetPinMessage by remember { mutableStateOf<String?>(null) }
    var manualRateInput by remember(settings.manualUsdRate) {
        mutableStateOf(settings.manualUsdRate?.toString().orEmpty())
    }

    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }

    fun ensureNotificationPermission(onGranted: (() -> Unit)? = null) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted?.invoke()
        } else {
            pendingNotificationAction = onGranted
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestPin(receiverClass: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val awm = context.getSystemService(AppWidgetManager::class.java)
            if (awm != null && awm.isRequestPinAppWidgetSupported) {
                val ok = awm.requestPinAppWidget(ComponentName(context, receiverClass), null, null)
                widgetPinMessage = if (ok) "ホーム画面への追加リクエストを送信しました。" else "お使いのホームアプリはピン留めに非対応です。ホーム画面の長押しから追加してください。"
            } else {
                widgetPinMessage = "ホーム画面の空きスペースを長押し →「ウィジェット」から配置してください。"
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("設定", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = colors.textPrimary)
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection("ホーム画面ウィジェット") {
                Text(
                    text = "一覧ウィジェット（複数アカウント表示）と個別ウィジェット（2x1コンパクト）をホーム画面に設置できます。",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, colors.border),
                        onClick = { requestPin(UsageOverviewWidgetReceiver::class.java) },
                    ) {
                        Icon(Icons.Outlined.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("一覧を追加", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, colors.border),
                        onClick = { requestPin(UsageSingleWidgetReceiver::class.java) },
                    ) {
                        Icon(Icons.Outlined.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("個別を追加", fontSize = 12.sp)
                    }
                }
                if (widgetPinMessage != null) {
                    Text(widgetPinMessage!!, color = colors.textMuted, fontSize = 11.sp)
                }
            }

            SettingsSection("更新と表示") {
                Text("自動更新間隔", color = colors.textSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RefreshIntervalOption.entries.forEach { opt ->
                        FilterChip(
                            selected = settings.refreshInterval == opt,
                            onClick = { onUpdateSettings { it.copy(refreshInterval = opt) } },
                            label = { Text(opt.labelJa, fontSize = 11.sp) },
                        )
                    }
                }

                Text("使用量の表示基準", color = colors.textSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UsageDisplayMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.displayMode == mode,
                            onClick = { onUpdateSettings { it.copy(displayMode = mode) } },
                            label = { Text(mode.labelJa, fontSize = 11.sp) },
                        )
                    }
                }

                SwitchRow(
                    title = "ペース目安線を表示",
                    checked = settings.showPaceMarkers,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showPaceMarkers = v) } },
                )
                SwitchRow(
                    title = "週間ペースを平日（月〜金）のみで計算",
                    checked = settings.workdaysOnlyWeeklyPace,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(workdaysOnlyWeeklyPace = v) } },
                )

                Text("リセット時刻表示", color = colors.textSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResetTimerMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.resetTimerMode == mode,
                            onClick = { onUpdateSettings { it.copy(resetTimerMode = mode) } },
                            label = { Text(mode.labelJa, fontSize = 11.sp) },
                        )
                    }
                }
                SwitchRow(
                    title = "24時間表記",
                    checked = settings.clock24h,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(clock24h = v) } },
                )
            }

            SettingsSection("テーマ・通貨") {
                Text("配色テーマ", color = colors.textSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppThemeMode.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.themeMode == theme,
                            onClick = { onUpdateSettings { it.copy(themeMode = theme) } },
                            label = { Text(theme.labelJa, fontSize = 11.sp) },
                        )
                    }
                }

                Text("表示通貨", color = colors.textSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurrencyFormatter.supportedCurrencies.forEach { cur ->
                        FilterChip(
                            selected = settings.displayCurrency == cur.code,
                            onClick = { onUpdateSettings { it.copy(displayCurrency = cur.code) } },
                            label = { Text(cur.code, fontSize = 11.sp) },
                        )
                    }
                }

                if (settings.displayCurrency != "USD") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualRateInput,
                            onValueChange = {
                                manualRateInput = it
                                val num = it.trim().toDoubleOrNull()?.takeIf { r -> r > 0.0 }
                                onUpdateSettings { s -> s.copy(manualUsdRate = num) }
                            },
                            label = { Text("手動 USD→${settings.displayCurrency} レート（任意）") },
                            placeholder = { Text("空欄で自動レート") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            SettingsSection("アラート通知") {
                SwitchRow(
                    title = "使用率しきい値通知",
                    checked = settings.notifyThresholdAlerts,
                    onCheckedChange = { v ->
                        if (v) ensureNotificationPermission()
                        onUpdateSettings { it.copy(notifyThresholdAlerts = v) }
                    },
                )
                Text("警告ライン1: ${settings.alertThreshold1}%", color = colors.textSecondary, fontSize = 12.sp)
                Slider(
                    value = settings.alertThreshold1.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(alertThreshold1 = v.roundToInt()) } },
                    valueRange = 20f..95f,
                )
                Text("警告ライン2: ${settings.alertThreshold2}%", color = colors.textSecondary, fontSize = 12.sp)
                Slider(
                    value = settings.alertThreshold2.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(alertThreshold2 = v.roundToInt()) } },
                    valueRange = 30f..99f,
                )
                SwitchRow(
                    title = "超過ペース（枯渇予測）時に通知",
                    checked = settings.notifyPaceAlerts,
                    onCheckedChange = { v ->
                        if (v) ensureNotificationPermission()
                        onUpdateSettings { it.copy(notifyPaceAlerts = v) }
                    },
                )
                SwitchRow(
                    title = "クォータリセット時に通知",
                    checked = settings.notifyResetAlerts,
                    onCheckedChange = { v ->
                        if (v) ensureNotificationPermission()
                        onUpdateSettings { it.copy(notifyResetAlerts = v) }
                    },
                )
                SwitchRow(
                    title = "使用量を通知バーに常時表示",
                    checked = settings.persistentNotificationEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            ensureNotificationPermission {
                                onUpdateSettings { it.copy(persistentNotificationEnabled = true) }
                            }
                        } else {
                            onUpdateSettings { it.copy(persistentNotificationEnabled = false) }
                        }
                    },
                )
                Text(
                    "アプリを閉じても、最後に取得した使用率を通知に残します。更新は自動更新間隔に従います。",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                )
            }

            SettingsSection("接続済みアカウント管理 (${accounts.size})") {
                if (accounts.isEmpty()) {
                    Text("まだプロバイダーが接続されていません。", color = colors.textMuted, fontSize = 12.sp)
                } else {
                    accounts.sortedBy { it.sortOrder }.forEachIndexed { idx, acc ->
                        val pid = ProviderId.fromId(acc.providerId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEditAccount(acc.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(ProviderVisuals.iconRes(acc.providerId)),
                                contentDescription = null,
                                tint = ProviderVisuals.iconTint(acc.providerId, colors.isDark),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.effectiveTitle(pid), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(if (acc.enabled) "有効" else "停止中", color = colors.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = acc.enabled,
                                onCheckedChange = { onToggleAccountEnabled(acc.id, it) },
                            )
                            if (idx > 0) {
                                IconButton(onClick = { onMoveAccount(acc.id, -1) }) {
                                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "上へ", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (idx < accounts.lastIndex) {
                                IconButton(onClick = { onMoveAccount(acc.id, 1) }) {
                                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "下へ", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(onClick = { onDeleteAccount(acc.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "削除", tint = colors.paceRed, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalOtuColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalOtuColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
