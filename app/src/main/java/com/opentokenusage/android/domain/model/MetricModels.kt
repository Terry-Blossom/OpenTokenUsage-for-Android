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
        val amount: Double,
        val currencySymbol: String = "$",
        val expiresAtMs: Long? = null,
        override val scope: String = "detail",
    ) : MetricLine()
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
