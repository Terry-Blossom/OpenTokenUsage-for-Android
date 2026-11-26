package com.opentokenusage.android.domain.logic

import java.math.BigDecimal

sealed interface ManualUsdRateUpdate {
    data object Ignore : ManualUsdRateUpdate
    data class Commit(val rate: Double?) : ManualUsdRateUpdate
}

fun formatManualUsdRate(rate: Double?): String {
    if (rate == null || !rate.isFinite() || rate <= 0.0) return ""
    return BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()
}

fun parseManualUsdRateInput(raw: String): ManualUsdRateUpdate {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ManualUsdRateUpdate.Commit(null)
    val parsed = trimmed.toDoubleOrNull()
    return if (parsed != null && parsed.isFinite() && parsed > 0.0) {
        ManualUsdRateUpdate.Commit(parsed)
    } else {
        ManualUsdRateUpdate.Ignore
    }
}

/**
 * Keeps local manual USD rate text stable while typing (e.g. "150.", "0.", "150.50"),
 * commits only blank or valid positive numbers, ignores echoes of its own async commits,
 * and syncs when the stored rate changes externally (e.g. settings import).
 */
class ManualUsdRateInputController(initialRate: Double?) {
    var text: String = formatManualUsdRate(initialRate)
        private set

    private var lastRequestedRate: Double? = initialRate
    private var lastObservedExternalRate: Double? = initialRate
    private val pendingCommits = ArrayDeque<Double?>()

    fun onUserInput(newText: String): ManualUsdRateUpdate {
        text = newText
        val parsed = parseManualUsdRateInput(newText)
        if (parsed is ManualUsdRateUpdate.Commit) {
            if (parsed.rate == lastRequestedRate) {
                return ManualUsdRateUpdate.Ignore
            }
            lastRequestedRate = parsed.rate
            pendingCommits.addLast(parsed.rate)
            return parsed
        }
        return ManualUsdRateUpdate.Ignore
    }

    /**
     * Returns updated text if [externalRate] represents a true external change,
     * or null when the local text should remain untouched.
     */
    fun onExternalRateChanged(externalRate: Double?): String? {
        val pendingIdx = pendingCommits.indexOfFirst { it == externalRate }
        if (pendingIdx >= 0) {
            repeat(pendingIdx + 1) { pendingCommits.removeFirst() }
            lastObservedExternalRate = externalRate
            return null
        }
        if (externalRate == lastObservedExternalRate) {
            return null
        }
        pendingCommits.clear()
        lastObservedExternalRate = externalRate
        lastRequestedRate = externalRate
        text = formatManualUsdRate(externalRate)
        return text
    }
}
