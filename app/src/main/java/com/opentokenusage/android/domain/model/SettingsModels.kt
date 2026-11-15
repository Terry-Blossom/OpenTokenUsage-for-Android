package com.opentokenusage.android.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RefreshIntervalOption(val minutes: Int?, val labelJa: String) {
    MANUAL(null, "手動のみ"),
    MIN_5(5, "5分ごと（アプリ表示中）"),
    MIN_15(15, "15分ごと"),
    MIN_30(30, "30分ごと"),
    HOUR_1(60, "60分ごと"),
    SMART(15, "スマート更新"),
}

@Serializable
enum class UsageDisplayMode(val labelJa: String) {
    LEFT("残り表示"),
    USED("使用済み表示"),
}

@Serializable
enum class ResetTimerMode(val labelJa: String) {
    RELATIVE("残り時間"),
    ABSOLUTE("日時表示"),
}

@Serializable
enum class AppThemeMode(val labelJa: String) {
    OLED_DARK("OLED ダーク"),
    LIGHT("ライト"),
    SYSTEM("端末設定に合わせる"),
}

@Serializable
data class AppSettings(
    val refreshInterval: RefreshIntervalOption = RefreshIntervalOption.MIN_15,
    val displayMode: UsageDisplayMode = UsageDisplayMode.LEFT,
    val workdaysOnlyWeeklyPace: Boolean = false,
    val resetTimerMode: ResetTimerMode = ResetTimerMode.RELATIVE,
    val clock24h: Boolean = true,
    val themeMode: AppThemeMode = AppThemeMode.OLED_DARK,
    val displayCurrency: String = "USD",
    val manualUsdRate: Double? = null,
    val alertThreshold1: Int = 75,
    val alertThreshold2: Int = 90,
    val notifyThresholdAlerts: Boolean = true,
    val notifyPaceAlerts: Boolean = false,
    val notifyResetAlerts: Boolean = false,
    val showPaceMarkers: Boolean = true,
    val overviewMaxLines: Int = 4,
)
