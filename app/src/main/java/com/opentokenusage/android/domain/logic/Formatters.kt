package com.opentokenusage.android.domain.logic

import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.ResetTimerMode
import com.opentokenusage.android.domain.model.UsageDisplayMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class CurrencyInfo(
    val code: String,
    val symbol: String,
    val defaultUsdRate: Double,
)

object CurrencyFormatter {
    val supportedCurrencies = listOf(
        CurrencyInfo("USD", "$", 1.0),
        CurrencyInfo("JPY", "¥", 154.0),
        CurrencyInfo("EUR", "€", 0.92),
        CurrencyInfo("GBP", "£", 0.79),
        CurrencyInfo("CAD", "CA$", 1.39),
        CurrencyInfo("AUD", "A$", 1.53),
        CurrencyInfo("CHF", "CHF ", 0.88),
        CurrencyInfo("CNY", "¥", 7.24),
        CurrencyInfo("INR", "₹", 84.4),
        CurrencyInfo("KRW", "₩", 1390.0),
        CurrencyInfo("BRL", "R$", 5.78),
        CurrencyInfo("MXN", "MX$", 20.1),
        CurrencyInfo("SEK", "kr ", 10.8),
    )

    fun infoFor(code: String): CurrencyInfo =
        supportedCurrencies.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: supportedCurrencies.first()

    fun formatUsdAmount(
        usdAmount: Double,
        currencyCode: String,
        manualRate: Double? = null,
        cachedRates: Map<String, Double> = emptyMap(),
    ): String {
        val info = infoFor(currencyCode)
        val rate = when {
            info.code == "USD" -> 1.0
            manualRate != null && manualRate > 0.0 -> manualRate
            else -> cachedRates[info.code] ?: info.defaultUsdRate
        }
        val converted = usdAmount * rate
        val decimals = if (info.code == "JPY" || info.code == "KRW") 0 else 2
        val nf = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = if (decimals == 0) 0 else if (converted % 1.0 == 0.0) 0 else 2
            maximumFractionDigits = decimals
        }
        return "${info.symbol}${nf.format(converted)}"
    }
}

object UsageDisplayFormatter {
    fun displayedValue(used: Double, limit: Double, mode: UsageDisplayMode): Double =
        when (mode) {
            UsageDisplayMode.LEFT -> max(0.0, limit - used)
            UsageDisplayMode.USED -> max(0.0, used)
        }

    fun formatMetricValue(
        used: Double,
        limit: Double,
        format: MetricFormat,
        settings: AppSettings,
        cachedRates: Map<String, Double> = emptyMap(),
    ): String {
        val displayed = displayedValue(used, limit, settings.displayMode)
        val suffixWord = if (settings.displayMode == UsageDisplayMode.LEFT) "残り" else "使用"
        return when (format) {
            is MetricFormat.Percent -> {
                val pct = if (limit > 0.0) ((displayed / limit) * 100.0).roundToInt().coerceIn(0, 100) else 0
                if (settings.displayMode == UsageDisplayMode.LEFT) "残り $pct%" else "${pct}% 使用"
            }
            is MetricFormat.Dollars -> {
                val main = CurrencyFormatter.formatUsdAmount(
                    displayed,
                    settings.displayCurrency,
                    settings.manualUsdRate,
                    cachedRates,
                )
                val total = CurrencyFormatter.formatUsdAmount(
                    limit,
                    settings.displayCurrency,
                    settings.manualUsdRate,
                    cachedRates,
                )
                "$main / $total ($suffixWord)"
            }
            is MetricFormat.Count -> {
                val u = formatCompactNumber(displayed)
                val l = formatCompactNumber(limit)
                val sfx = if (format.suffix.isNotBlank()) " ${format.suffix}" else ""
                "$u / $l$sfx ($suffixWord)"
            }
        }
    }

    fun formatPercentShort(used: Double, limit: Double, mode: UsageDisplayMode): String {
        val value = displayedValue(used, limit, mode)
        val pct = if (limit > 0.0) ((value / limit) * 100.0).roundToInt().coerceIn(0, 100) else 0
        return "$pct%"
    }

    fun formatCompactNumber(value: Double): String {
        if (value % 1.0 == 0.0) return value.toLong().toString()
        return String.format(Locale.US, "%.1f", value)
    }

    fun formatDurationJa(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        if (totalMinutes < 1L) return "1分未満"
        val days = totalMinutes / (60L * 24L)
        val hours = (totalMinutes % (60L * 24L)) / 60L
        val mins = totalMinutes % 60L
        return buildString {
            if (days > 0) append("${days}日")
            if (hours > 0) append("${hours}時間")
            if (days == 0L && mins > 0) append("${mins}分")
        }
    }

    fun formatResetTime(
        resetsAtMs: Long?,
        nowMs: Long,
        mode: ResetTimerMode,
        clock24h: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (resetsAtMs == null) return null
        val diff = resetsAtMs - nowMs
        if (diff <= 0L) return "まもなくリセット"

        return when (mode) {
            ResetTimerMode.RELATIVE -> "${formatDurationJa(diff)}後"
            ResetTimerMode.ABSOLUTE -> {
                val target = Instant.ofEpochMilli(resetsAtMs).atZone(zoneId)
                val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
                val timeFmt = DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "a h:mm", Locale.JAPAN)
                val dateFmt = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
                if (target.toLocalDate() == today) {
                    "${target.format(timeFmt)} リセット"
                } else {
                    "${target.format(dateFmt)} ${target.format(timeFmt)}"
                }
            }
        }
    }

    fun formatPaceDetailJa(
        pace: PaceResult?,
        limit: Double,
        format: MetricFormat,
        settings: AppSettings,
        cachedRates: Map<String, Double> = emptyMap(),
    ): String? {
        if (pace == null) return null
        val prefix = when (pace.tier) {
            PaceTier.AHEAD -> "余裕あり"
            PaceTier.ON_TRACK -> "標準ペース"
            PaceTier.BEHIND -> "超過ペース"
        }
        val extra = buildList {
            if (pace.deficit != null && pace.deficit > 0.0) {
                val defStr = when (format) {
                    is MetricFormat.Percent -> "${pace.deficit.roundToInt()}%"
                    is MetricFormat.Dollars -> CurrencyFormatter.formatUsdAmount(
                        pace.deficit,
                        settings.displayCurrency,
                        settings.manualUsdRate,
                        cachedRates,
                    )
                    is MetricFormat.Count -> formatCompactNumber(pace.deficit)
                }
                add("+${defStr}超過")
            }
            if (pace.etaToLimitMs != null) {
                add("約${formatDurationJa(pace.etaToLimitMs)}で上限")
            }
        }
        return if (extra.isEmpty()) prefix else "$prefix (${extra.joinToString("・")})"
    }

    fun formatTimeHm(timestampMs: Long, clock24h: Boolean, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "h:mm", Locale.JAPAN)
        return Instant.ofEpochMilli(timestampMs).atZone(zoneId).format(fmt)
    }

    fun resetCreditKindLabelJa(kind: String?): String = when (kind) {
        "fable" -> "Fable リセット"
        "session" -> "5時間枠リセット"
        "weekly" -> "週間枠リセット"
        "full" -> "全体リセット"
        else -> "リセット"
    }

    fun resetCreditKindDescriptionJa(kind: String?): String? = when (kind) {
        "fable" -> "5時間枠・Fable枠をリセット（週間枠はFable使用分のみ返還）"
        else -> null
    }

    fun formatResetCreditExpiryJa(
        expiresAtMs: Long?,
        nowMs: Long,
        clock24h: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (expiresAtMs == null) return "期限不明"
        val diff = expiresAtMs - nowMs
        if (diff <= 0L) return "期限切れ"
        val target = Instant.ofEpochMilli(expiresAtMs).atZone(zoneId)
        val dateFmt = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
        val timeFmt = DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "a h:mm", Locale.JAPAN)
        return "あと${formatDurationJa(diff)} (${target.format(dateFmt)} ${target.format(timeFmt)} まで)"
    }
}
