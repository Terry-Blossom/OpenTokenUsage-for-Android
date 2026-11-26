package com.opentokenusage.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class MetricFormat {
    @Serializable
    @SerialName("percent")
    data object Percent : MetricFormat()

    @Serializable
    @SerialName("dollars")
    data object Dollars : MetricFormat()

    @Serializable
    @SerialName("count")
    data class Count(val suffix: String = "") : MetricFormat()
}

@Serializable
data class ResetCreditItem(
    val count: Int = 1,
    val expiresAtMs: Long? = null,
    val kind: String? = null,
)

fun groupResetCreditItems(items: List<ResetCreditItem>): List<ResetCreditItem> {
    if (items.isEmpty()) return emptyList()
    val grouped = linkedMapOf<Pair<String?, Long?>, Int>()
    for (item in items) {
        if (item.count <= 0) continue
        val key = item.kind to item.expiresAtMs
        grouped[key] = (grouped[key] ?: 0) + item.count
    }
    return grouped.entries
        .map { (key, count) -> ResetCreditItem(count = count, expiresAtMs = key.second, kind = key.first) }
        .sortedWith(
            compareBy<ResetCreditItem>(
                { it.expiresAtMs == null },
                { it.expiresAtMs ?: Long.MAX_VALUE },
                { it.kind ?: "" },
            ),
        )
}

@Serializable
sealed class MetricLine {
    abstract val label: String
    abstract val scope: String // "overview" or "detail"

    @Serializable
    @SerialName("progress")
    data class Progress(
        override val label: String,
        val used: Double,
        val limit: Double,
        val format: MetricFormat = MetricFormat.Percent,
        val resetsAtMs: Long? = null,
        val periodDurationMs: Long? = null,
        val colorHex: String? = null,
        val primaryOrder: Int? = null,
        val period: String? = null, // e.g. "weekly"
        override val scope: String = "overview",
    ) : MetricLine() {
        val usedFraction: Double
            get() = if (limit > 0.0) (used / limit).coerceIn(0.0, 1.0) else 0.0

        val remainingFraction: Double
            get() = (1.0 - usedFraction).coerceIn(0.0, 1.0)
    }

    @Serializable
    @SerialName("text")
    data class Text(
        override val label: String,
        val value: String,
        val colorHex: String? = null,
        val subtitle: String? = null,
        override val scope: String = "overview",
    ) : MetricLine()

    @Serializable
    @SerialName("badge")
    data class Badge(
        override val label: String,
        val text: String,
        val colorHex: String? = null,
        val subtitle: String? = null,
        override val scope: String = "overview",
    ) : MetricLine()

    @Serializable
    @SerialName("barChart")
    data class BarChart(
        override val label: String,
        val values: List<Double>,
        override val scope: String = "detail",
    ) : MetricLine()

    @Serializable
    @SerialName("resetCredit")
    data class ResetCredit(
        override val label: String,
        val amount: Double = 0.0,
        val currencySymbol: String = "$",
        val expiresAtMs: Long? = null,
        val availableCount: Int = 0,
        val items: List<ResetCreditItem> = emptyList(),
        override val scope: String = "detail",
    ) : MetricLine() {
        fun activeItems(nowMs: Long): List<ResetCreditItem> =
            if (items.isNotEmpty()) {
                items.filter { it.count > 0 && (it.expiresAtMs == null || it.expiresAtMs > nowMs) }
            } else if (availableCount > 0 && (expiresAtMs == null || expiresAtMs > nowMs)) {
                listOf(ResetCreditItem(count = availableCount, expiresAtMs = expiresAtMs))
            } else {
                emptyList()
            }

        fun activeCount(nowMs: Long): Int = activeItems(nowMs).sumOf { it.count }
    }
}

@Serializable
data class ProviderSnapshot(
    val accountId: String,
    val providerId: String,
    val displayName: String,
    val accountLabel: String = "",
    val plan: String? = null,
    val lines: List<MetricLine> = emptyList(),
    val fetchedAtMs: Long,
    val errorMessage: String? = null,
    val isStale: Boolean = false,
) {
    fun overviewLines(): List<MetricLine> =
        lines.filter { it.scope != "detail" }

    fun primaryProgressLines(maxCount: Int = 2): List<MetricLine.Progress> =
        lines.filterIsInstance<MetricLine.Progress>()
            .sortedWith(compareBy<MetricLine.Progress> { it.primaryOrder ?: Int.MAX_VALUE })
            .take(maxCount)
}

@Serializable
data class UsageHistoryEntry(
    val timestampMs: Long,
    val lineLabel: String,
    val usedPercent: Double,
)
