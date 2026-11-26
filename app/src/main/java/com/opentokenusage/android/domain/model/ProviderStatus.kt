package com.opentokenusage.android.domain.model

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.objOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Public Statuspage / incident.io service status snapshot for a provider.
 * Matches desktop `provider_status.rs` ("none" | "minor" | "major" | "critical" | "maintenance").
 */
@Serializable
data class ProviderServiceStatus(
    val indicator: String,
    val description: String = "",
    val checkedAtMs: Long = 0L,
) {
    val isOutage: Boolean
        get() = isOutageIndicator(indicator)

    companion object {
        /**
         * Returns true when a Statuspage indicator signals a non-operational state
         * (anything non-blank other than "none", e.g. minor / major / critical / maintenance).
         */
        fun isOutageIndicator(indicator: String?): Boolean {
            val normalized = indicator?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return normalized.isNotEmpty() && normalized != "none"
        }

        /**
         * Parses Atlassian Statuspage / incident.io `/api/v2/status.json` payload.
         * Returns null for HTML or malformed JSON bodies.
         */
        fun parseStatusJson(body: String, checkedAtMs: Long = 0L): ProviderServiceStatus? {
            val root = ProviderHttpSupport.tryParseObject(body) ?: return null
            val statusObj = root.objOrNull("status") ?: return null
            val indicatorPrim = statusObj["indicator"] as? JsonPrimitive ?: return null
            val descriptionPrim = statusObj["description"] as? JsonPrimitive ?: return null
            if (!indicatorPrim.isString || !descriptionPrim.isString) return null
            val indicator = indicatorPrim.content.trim()
            if (indicator.isEmpty()) return null
            return ProviderServiceStatus(
                indicator = indicator,
                description = descriptionPrim.content.trim(),
                checkedAtMs = checkedAtMs,
            )
        }
    }
}

enum class FetchFailureCause {
    SERVICE_OUTAGE,
    AUTH,
    NETWORK,
    LOCAL_OR_OTHER,
}

data class FetchFailureDiagnosis(
    val cause: FetchFailureCause,
    val summaryJa: String,
    val serviceDescription: String? = null,
)

/**
 * Classifies a provider fetch failure into "is it them (service outage) or me (auth/network/local)".
 */
fun diagnoseFetchFailure(
    errorMessage: String?,
    serviceStatus: ProviderServiceStatus?,
    isAuthError: Boolean = false,
    statusCode: Int? = null,
): FetchFailureDiagnosis? {
    if (errorMessage.isNullOrBlank()) return null
    if (serviceStatus?.isOutage == true) {
        return FetchFailureDiagnosis(
            cause = FetchFailureCause.SERVICE_OUTAGE,
            summaryJa = "サービス側の障害の可能性",
            serviceDescription = serviceStatus.description.trim().takeIf { it.isNotEmpty() },
        )
    }
    val lower = errorMessage.lowercase(Locale.ROOT)
    val looksLikeAuth = isAuthError ||
        statusCode == 401 ||
        statusCode == 403 ||
        "認証" in errorMessage ||
        "トークン" in errorMessage ||
        "apiキー" in lower ||
        "api key" in lower ||
        "cookie" in lower ||
        "セッション" in errorMessage ||
        "ログイン" in errorMessage ||
        "unauthorized" in lower ||
        "forbidden" in lower ||
        "invalid_grant" in lower
    if (looksLikeAuth) {
        return FetchFailureDiagnosis(
            cause = FetchFailureCause.AUTH,
            summaryJa = "認証エラー（認証情報・設定を確認）",
        )
    }
    val looksLikeNetwork = statusCode == null && (
        "通信エラー" in errorMessage ||
            "接続" in errorMessage ||
            "タイムアウト" in errorMessage ||
            "timeout" in lower ||
            "network" in lower ||
            "offline" in lower ||
            "unable to resolve host" in lower ||
            "failed to connect" in lower
        )
    if (looksLikeNetwork) {
        return FetchFailureDiagnosis(
            cause = FetchFailureCause.NETWORK,
            summaryJa = "ネットワークエラー（端末の通信状況を確認）",
        )
    }
    return FetchFailureDiagnosis(
        cause = FetchFailureCause.LOCAL_OR_OTHER,
        summaryJa = "取得エラー（認証・ネットワーク等を確認）",
    )
}
