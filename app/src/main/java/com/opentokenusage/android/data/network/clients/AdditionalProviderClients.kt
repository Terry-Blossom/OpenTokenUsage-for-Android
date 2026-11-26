package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

private fun requireBearerKey(secret: AccountSecret, name: String): String =
    secret.apiKey.ifBlank { secret.accessToken }.trim().takeIf { it.isNotEmpty() }
        ?: throw ProviderProbeException("$name の API キーが未設定です", isAuthError = true)

/**
 * Moonshot (Kimi) platform pay-as-you-go balance client.
 * Tries global (.ai) first, then mainland China (.cn), matching desktop plugin-kimi-api.js.
 */
class KimiApiClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {

    private data class BalanceEndpoint(
        val url: String,
        val sign: String,
        val regionLabel: String,
    )

    override suspend fun probe(
        account: ConfiguredAccount,
        secret: AccountSecret,
        nowMs: Long,
    ): ProbeResult {
        val apiKey = requireBearerKey(secret, "Kimi API")
        val endpoints = if (overrides.baseUrls.containsKey("kimi-api")) {
            listOf(
                BalanceEndpoint(
                    url = "${overrides.resolve("kimi-api", "https://api.moonshot.ai")}/v1/users/me/balance",
                    sign = "$",
                    regionLabel = "Global",
                ),
            )
        } else {
            listOf(
                BalanceEndpoint(
                    url = "${overrides.resolve("kimi-api-global", "https://api.moonshot.ai")}/v1/users/me/balance",
                    sign = "$",
                    regionLabel = "Global",
                ),
                BalanceEndpoint(
                    url = "${overrides.resolve("kimi-api-cn", "https://api.moonshot.cn")}/v1/users/me/balance",
                    sign = "¥",
                    regionLabel = "CN",
                ),
            )
        }

        val headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")
        var refusedCount = 0
        var lastFailure: ProviderProbeException = ProviderProbeException("Kimi API に接続できません")

        for (endpoint in endpoints) {
            val resp = try {
                ProviderHttpSupport.get(http, endpoint.url, headers)
            } catch (e: ProviderProbeException) {
                lastFailure = e
                continue
            }
            if (resp.statusCode == 401 || resp.statusCode == 403) {
                refusedCount += 1
                continue
            }
            if (resp.statusCode == 429) {
                lastFailure = ProviderProbeException(
                    message = "Kimi API がレート制限中 (HTTP 429)",
                    isAuthError = false,
                    retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(resp.firstHeader("Retry-After")),
                    statusCode = 429,
                )
                continue
            }
            if (resp.statusCode !in 200..299) {
                lastFailure = ProviderProbeException(
                    message = "Kimi API の取得に失敗 (HTTP ${resp.statusCode})",
                    statusCode = resp.statusCode,
                )
                continue
            }

            val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            val data = root?.objOrNull("data") ?: root
            val available = data?.doubleOrNull("available_balance")
            if (available == null) {
                lastFailure = ProviderProbeException("Kimi API のレスポンス解析に失敗")
                continue
            }

            fun money(v: Double): String = String.format(Locale.US, "%s%.2f", endpoint.sign, v)

            val lines = mutableListOf<MetricLine>(
                MetricLine.Text(
                    label = "残高",
                    value = money(available),
                    colorHex = if (available <= 0.0) "#EF4444" else null,
                    scope = "overview",
                ),
            )
            val vouchers = data.doubleOrNull("voucher_balance")
            if (vouchers != null && vouchers > 0.0) {
                lines += MetricLine.Text(label = "バウチャー", value = money(vouchers), scope = "detail")
            }
            val cash = data.doubleOrNull("cash_balance")
            if (cash != null) {
                lines += MetricLine.Text(label = "チャージ残高", value = money(cash), scope = "detail")
            }
            return ProbeResult(plan = endpoint.regionLabel, lines = lines)
        }

        if (refusedCount == endpoints.size) {
            throw ProviderProbeException(
                "Kimi API の認証に失敗しました。API キーを確認してください。",
                isAuthError = true,
                statusCode = 401,
            )
        }
        throw lastFailure
    }
}

/**
 * Kilo (kilo.ai) client reading credit blocks and Kilo Pass state via tRPC batch API.
 * Matches desktop plugin-kilo.js.
 */
class KiloClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {

    override suspend fun probe(
        account: ConfiguredAccount,
        secret: AccountSecret,
        nowMs: Long,
    ): ProbeResult {
        val token = requireBearerKey(secret, "Kilo")
        val base = overrides.resolve("kilo", "https://app.kilo.ai")
        val url = "$base$TRPC_BATCH_PATH"
        val resp = ProviderHttpSupport.get(
            http,
            url,
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
        )
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException(
                "Kilo の認証に失敗しました (HTTP ${resp.statusCode})",
                isAuthError = true,
                statusCode = resp.statusCode,
            )
        }
        if (resp.statusCode == 429) {
            throw ProviderProbeException(
                "Kilo がレート制限中 (HTTP 429)",
                isAuthError = false,
                retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(resp.firstHeader("Retry-After")),
                statusCode = 429,
            )
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Kilo の取得に失敗 (HTTP ${resp.statusCode})", statusCode = resp.statusCode)
        }

        val batch = ProviderHttpSupport.tryParseArray(resp.bodyText)
            ?: throw ProviderProbeException("Kilo のレスポンス解析に失敗")

        val lines = mutableListOf<MetricLine>()
        var plan: String? = null

        // [0] user.getCreditBlocks (amounts in millionths of USD)
        val blocks = unwrapTrpc(batch.getOrNull(0))
        val rows = when (blocks) {
            is JsonObject -> blocks.arrOrNull("creditBlocks") ?: JsonArray(emptyList())
            is JsonArray -> blocks
            else -> JsonArray(emptyList())
        }
        val total = sumField(rows, "amount_mUsd") / 1_000_000.0
        val left = sumField(rows, "balance_mUsd") / 1_000_000.0
        if (total > 0.0) {
            lines += MetricLine.Progress(
                label = "Credits",
                used = max(0.0, total - left),
                limit = total,
                format = MetricFormat.Dollars,
                primaryOrder = 1,
                scope = "overview",
            )
        }

        // [1] kiloPass.getState
        val pass = unwrapTrpc(batch.getOrNull(1)) as? JsonObject
        val rawSub = pass?.objOrNull("subscription")
        val status = rawSub?.strOrNull("status")
        val sub = if (rawSub != null && (status == null || status in LIVE_PASS_STATUSES)) rawSub else null
        if (sub != null) {
            val used = sub.doubleOrNull("currentPeriodUsageUsd")
            val limit = (sub.doubleOrNull("currentPeriodBaseCreditsUsd") ?: 0.0) +
                (sub.doubleOrNull("currentPeriodBonusCreditsUsd") ?: 0.0)
            val resetsAtMs = ProviderHttpSupport.parseDateMs(
                sub["nextBillingAt"] ?: sub["nextRenewalAt"] ?: sub["renewsAt"] ?: sub["renewAt"],
            )
            if (used != null && limit > 0.0) {
                lines += MetricLine.Progress(
                    label = "Kilo Pass",
                    used = min(used, limit).coerceAtLeast(0.0),
                    limit = limit,
                    format = MetricFormat.Dollars,
                    resetsAtMs = resetsAtMs,
                    periodDurationMs = MONTH_MS,
                    primaryOrder = if (lines.isEmpty()) 1 else 2,
                    period = "monthly",
                    scope = "overview",
                )
            }
            val tier = sub.strOrNull("tier")
            plan = TIER_LABELS[tier] ?: tier ?: "Kilo Pass"
        }

        // Fresh account with zero blocks and no Pass
        if (lines.isEmpty() && blocks is JsonObject) {
            val balance = (blocks.doubleOrNull("totalBalance_mUsd") ?: 0.0) / 1_000_000.0
            lines += MetricLine.Text(
                label = "Credits",
                value = String.format(Locale.US, "$%.2f", balance),
                scope = "overview",
            )
        }

        if (lines.isEmpty()) {
            throw ProviderProbeException("Kilo のレスポンス解析に失敗")
        }
        return ProbeResult(plan = plan, lines = lines)
    }

    private fun unwrapTrpc(entry: JsonElement?): JsonElement? {
        val result = (entry as? JsonObject)?.objOrNull("result") ?: return null
        val data = result["data"]
        if (data is JsonObject && data.containsKey("json")) {
            return data["json"]
        }
        if (data != null) return data
        return result["json"]
    }

    private fun sumField(rows: JsonArray, key: String): Double {
        var total = 0.0
        for (item in rows) {
            val obj = item as? JsonObject ?: continue
            val value = obj.doubleOrNull(key)
            if (value != null) total += value
        }
        return total
    }

    companion object {
        private const val TRPC_BATCH_PATH =
            "/api/trpc/user.getCreditBlocks,kiloPass.getState?batch=1&input=%7B%220%22%3A%7B%22json%22%3Anull%7D%2C%221%22%3A%7B%22json%22%3Anull%7D%7D"
        private const val MONTH_MS = 30L * 24L * 3600_000L
        private val LIVE_PASS_STATUSES = setOf("active", "past_due", "trialing")
        private val TIER_LABELS = mapOf(
            "tier_19" to "Starter",
            "tier_49" to "Pro",
            "tier_199" to "Expert",
        )
    }
}

/**
 * Codebuff (codebuff.com) client reading credits and optional weekly subscription limit.
 * Matches desktop plugin-codebuff.js.
 */
class CodebuffClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {

    override suspend fun probe(
        account: ConfiguredAccount,
        secret: AccountSecret,
        nowMs: Long,
    ): ProbeResult {
        val token = requireBearerKey(secret, "Codebuff")
        val base = overrides.resolve("codebuff", "https://www.codebuff.com")
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

        val usageResp = ProviderHttpSupport.postJson(
            http,
            "$base/api/v1/usage",
            """{"fingerprintId":"opentokenusage"}""",
            headers,
        )
        if (usageResp.statusCode == 401 || usageResp.statusCode == 403) {
            throw ProviderProbeException(
                "Codebuff の認証に失敗しました (HTTP ${usageResp.statusCode})",
                isAuthError = true,
                statusCode = usageResp.statusCode,
            )
        }
        if (usageResp.statusCode == 429) {
            throw ProviderProbeException(
                "Codebuff がレート制限中 (HTTP 429)",
                isAuthError = false,
                retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(usageResp.firstHeader("Retry-After")),
                statusCode = 429,
            )
        }
        if (usageResp.statusCode !in 200..299) {
            throw ProviderProbeException(
                "Codebuff の取得に失敗 (HTTP ${usageResp.statusCode})",
                statusCode = usageResp.statusCode,
            )
        }

        val usage = ProviderHttpSupport.tryParseObject(usageResp.bodyText)
            ?: throw ProviderProbeException("Codebuff のレスポンス解析に失敗")

        val lines = mutableListOf<MetricLine>()
        val used = usage.doubleOrNull("usage") ?: usage.doubleOrNull("used")
        val quotaRaw = usage.doubleOrNull("quota") ?: usage.doubleOrNull("limit")
        val remaining = usage.doubleOrNull("remainingBalance") ?: usage.doubleOrNull("remaining")
        val total = quotaRaw ?: if (used != null && remaining != null) used + remaining else null
        val resetsAtMs = ProviderHttpSupport.parseDateMs(usage["next_quota_reset"])

        if (used != null && total != null && total > 0.0) {
            lines += MetricLine.Progress(
                label = "Credits",
                used = min(used, total).coerceAtLeast(0.0),
                limit = total,
                format = MetricFormat.Count("credits"),
                resetsAtMs = resetsAtMs,
                primaryOrder = 1,
                scope = "overview",
            )
        } else if (remaining != null) {
            lines += MetricLine.Text(
                label = "Credits",
                value = "${remaining.roundToLong()} credits",
                scope = "overview",
            )
        }

        var plan: String? = null
        val subResp = runCatching {
            ProviderHttpSupport.get(http, "$base/api/user/subscription", headers)
        }.getOrNull()
        val sub = if (subResp != null && subResp.statusCode in 200..299) {
            ProviderHttpSupport.tryParseObject(subResp.bodyText)
        } else {
            null
        }
        if (sub != null) {
            val inner = sub.objOrNull("subscription")
            plan = inner?.strOrNull("displayName")
                ?: sub.strOrNull("displayName")
                ?: inner?.strOrNull("tier")
                ?: sub.strOrNull("tier")
            val rate = sub.objOrNull("rateLimit")
            val weekUsed = rate?.doubleOrNull("weeklyUsed") ?: rate?.doubleOrNull("used")
            val weekLimit = rate?.doubleOrNull("weeklyLimit") ?: rate?.doubleOrNull("limit")
            if (weekUsed != null && weekLimit != null && weekLimit > 0.0) {
                lines += MetricLine.Progress(
                    label = "Weekly",
                    used = ((weekUsed / weekLimit) * 100.0).coerceIn(0.0, 100.0),
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = ProviderHttpSupport.parseDateMs(rate?.get("weeklyResetsAt")),
                    periodDurationMs = WEEK_MS,
                    primaryOrder = if (lines.isEmpty()) 1 else 2,
                    period = "weekly",
                    scope = "overview",
                )
            }
        }

        if (lines.isEmpty()) {
            throw ProviderProbeException("Codebuff のレスポンス解析に失敗")
        }
        return ProbeResult(plan = plan, lines = lines)
    }

    companion object {
        private const val WEEK_MS = 7L * 24L * 3600_000L
    }
}
