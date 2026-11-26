package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.boolOrNull
import com.opentokenusage.android.data.network.longOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.CustomBalanceAuthHeader
import com.opentokenusage.android.domain.model.CustomBalanceConfig
import com.opentokenusage.android.domain.model.CustomBalancePreset
import com.opentokenusage.android.domain.model.MetricLine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.math.max

/**
 * Custom balances client supporting New API / One API (account or API key),
 * Sub2API, and arbitrary GET JSON endpoints mapped with dot paths.
 */
class CustomBalanceClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {

    private data class BalanceReadResult(
        val unlimited: Boolean = false,
        val left: Double = 0.0,
        val used: Double? = null,
        val total: Double? = null,
        val currency: String = "USD",
    )

    override suspend fun probe(
        account: ConfiguredAccount,
        secret: AccountSecret,
        nowMs: Long,
    ): ProbeResult {
        val token = secret.apiKey.ifBlank { secret.accessToken }.trim().takeIf { it.isNotEmpty() }
            ?: throw ProviderProbeException("Custom balances の API キーが未設定です", isAuthError = true)

        val config = account.customBalance ?: CustomBalanceConfig()
        val rawBase = overrides.resolve("custom-balance", account.customHost.trim())
        val base = normalizeBaseUrl(rawBase, config.preset)

        val result = when (config.preset) {
            CustomBalancePreset.NEW_API -> readNewApiAccount(base, token, config.userId.trim())
            CustomBalancePreset.NEW_API_TOKEN -> readNewApiToken(base, token)
            CustomBalancePreset.SUB2API -> readSub2Api(base, token)
            CustomBalancePreset.CUSTOM -> readCustomEndpoint(base, token, config)
        }

        val planBadge = when (config.preset) {
            CustomBalancePreset.NEW_API -> "New API"
            CustomBalancePreset.NEW_API_TOKEN -> "New API Key"
            CustomBalancePreset.SUB2API -> "Sub2API"
            CustomBalancePreset.CUSTOM -> result.currency
        }

        val lines = mutableListOf<MetricLine>()
        if (result.unlimited) {
            lines += MetricLine.Text(
                label = "残高",
                value = "∞",
                subtitle = "無制限",
                scope = "overview",
            )
        } else {
            val total = result.total ?: result.used?.let { result.left + it }
            val valueText = if (total != null && total > 0.0) {
                "${formatMoney(result.left, result.currency)} / ${formatMoney(total, result.currency)}"
            } else {
                formatMoney(result.left, result.currency)
            }
            lines += MetricLine.Text(
                label = "残高",
                value = valueText,
                colorHex = if (result.left <= 0.0) "#EF4444" else null,
                scope = "overview",
            )
            if (result.used != null) {
                lines += MetricLine.Text(
                    label = "使用済み",
                    value = formatMoney(result.used, result.currency),
                    scope = "detail",
                )
            }
            if (total != null && total > 0.0) {
                lines += MetricLine.Text(
                    label = "合計枠",
                    value = formatMoney(total, result.currency),
                    scope = "detail",
                )
            }
        }

        return ProbeResult(plan = planBadge, lines = lines)
    }

    private suspend fun readNewApiAccount(base: String, token: String, userId: String): BalanceReadResult {
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            if (userId.isNotEmpty()) put("New-Api-User", userId)
        }
        val root = getJsonObject("$base/api/user/self", headers)
        val self = root.objOrNull("data") ?: JsonObject(emptyMap())
        val quota = amount(self["quota"])
        if (self.boolOrNull("unlimited_quota") == true || quota == -1.0) {
            return BalanceReadResult(unlimited = true)
        }
        if (quota == null) {
            throw ProviderProbeException("Custom balances のレスポンス解析に失敗")
        }
        val unit = fetchQuotaUnit(base)
        val usedQuota = amount(self["used_quota"])
        return BalanceReadResult(
            left = max(0.0, quota) / unit,
            used = usedQuota?.let { max(0.0, it) / unit },
            currency = "USD",
        )
    }

    private suspend fun readNewApiToken(base: String, token: String): BalanceReadResult {
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val root = getJsonObject("$base/api/usage/token/", headers)
        val usage = root.objOrNull("data") ?: JsonObject(emptyMap())
        if (usage.boolOrNull("unlimited_quota") == true) {
            return BalanceReadResult(unlimited = true)
        }
        val available = amount(usage["total_available"])
            ?: throw ProviderProbeException("Custom balances のレスポンス解析に失敗")
        val unit = fetchQuotaUnit(base)
        val spent = amount(usage["total_used"])
        return BalanceReadResult(
            left = max(0.0, available) / unit,
            used = spent?.let { max(0.0, it) / unit },
            currency = "USD",
        )
    }

    private suspend fun readSub2Api(base: String, token: String): BalanceReadResult {
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val root = getJsonObject("$base/api/v1/auth/me", headers)
        val balance = if (root.longOrNull("code") == 0L) {
            amount(root.objOrNull("data")?.get("balance"))
        } else {
            null
        } ?: throw ProviderProbeException("Custom balances のレスポンス解析に失敗")
        return BalanceReadResult(left = balance, used = null, currency = "USD")
    }

    private suspend fun readCustomEndpoint(
        base: String,
        token: String,
        config: CustomBalanceConfig,
    ): BalanceReadResult {
        val authHeaders = when (config.authHeader) {
            CustomBalanceAuthHeader.X_API_KEY -> mapOf("x-api-key" to token, "Accept" to "application/json")
            CustomBalanceAuthHeader.BEARER -> mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        }
        val rawPath = config.path.trim()
        val normalizedPath = when {
            rawPath.isEmpty() -> ""
            rawPath.startsWith("/") -> rawPath
            else -> "/$rawPath"
        }
        val body = getJsonObject("$base$normalizedPath", authHeaders)
        if (body.boolOrNull("success") == false || body.boolOrNull("code") == false) {
            throw ProviderProbeException("Custom balances のレスポンスがエラーを返しました")
        }
        val divisor = config.divisor.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        fun scaled(path: String): Double? {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return null
            val value = amount(atDotPath(body, trimmed)) ?: return null
            return value / divisor
        }
        val left = scaled(config.remainingPath)
            ?: throw ProviderProbeException("Custom balances の残高パス (${config.remainingPath.ifBlank { "未設定" }}) が取得できません")
        val used = scaled(config.usedPath)
        val total = scaled(config.totalPath)
        if (total != null && total < left) {
            throw ProviderProbeException("Custom balances の合計値が残高より小さいため不正です")
        }
        val currency = config.currency.trim().uppercase(Locale.ROOT).ifEmpty { "USD" }
        return BalanceReadResult(left = left, used = used, total = total, currency = currency)
    }

    private suspend fun fetchQuotaUnit(base: String): Double {
        return runCatching {
            val status = getJsonObject("$base/api/status", mapOf("Accept" to "application/json"))
            val unit = amount(status.objOrNull("data")?.get("quota_per_unit"))
            if (unit != null && unit > 0.0) unit else NEWAPI_DEFAULT_UNIT
        }.getOrElse { NEWAPI_DEFAULT_UNIT }
    }

    private suspend fun getJsonObject(url: String, headers: Map<String, String>): JsonObject {
        val resp = ProviderHttpSupport.get(http, url, headers)
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException(
                "Custom balances の認証に失敗しました (HTTP ${resp.statusCode})",
                isAuthError = true,
                statusCode = resp.statusCode,
            )
        }
        if (resp.statusCode == 429) {
            throw ProviderProbeException(
                "Custom balances がレート制限中 (HTTP 429)",
                isAuthError = false,
                retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(resp.firstHeader("Retry-After")),
                statusCode = 429,
            )
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException(
                "Custom balances の取得に失敗 (HTTP ${resp.statusCode})",
                statusCode = resp.statusCode,
            )
        }
        return ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Custom balances のレスポンス解析に失敗")
    }

    companion object {
        const val NEWAPI_DEFAULT_UNIT = 500_000.0

        internal fun normalizeBaseUrl(raw: String, preset: CustomBalancePreset): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                throw ProviderProbeException("ベース URL を入力してください")
            }
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                throw ProviderProbeException("ベース URL は http:// または https:// から入力してください")
            }
            return if (preset == CustomBalancePreset.CUSTOM) {
                trimmed
            } else {
                trimmed.replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
            }
        }

        internal fun amount(element: JsonElement?): Double? {
            if (element == null || element is JsonNull) return null
            val prim = element as? JsonPrimitive ?: return null
            val num = prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull() ?: return null
            return num.takeIf { it.isFinite() }
        }

        internal fun atDotPath(root: JsonObject, path: String): JsonElement? {
            if (path.isBlank()) return null
            var current: JsonElement = root
            for (part in path.split('.')) {
                val obj = current as? JsonObject ?: return null
                if (!obj.containsKey(part)) return null
                current = obj[part] ?: return null
            }
            return current
        }

        internal fun formatMoney(value: Double, currency: String): String {
            val upper = currency.trim().uppercase(Locale.ROOT).ifEmpty { "USD" }
            return if (upper == "USD") {
                String.format(Locale.US, "$%.2f", value)
            } else {
                String.format(Locale.US, "%.2f %s", value, upper)
            }
        }
    }
}
