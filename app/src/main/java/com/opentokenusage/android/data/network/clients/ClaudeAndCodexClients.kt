package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.boolOrNull
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlin.math.max

class ClaudeClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var currentSecret = secret
        var accessToken = secret.accessToken.trim()
        val refreshToken = secret.refreshToken.trim()
        if (accessToken.isEmpty() && refreshToken.isEmpty()) {
            throw ProviderProbeException("Claude の認証トークンが未設定です", isAuthError = true)
        }

        // Claude uses single-use refresh tokens with REFRESH_BUFFER_MS = 0: refresh only when expired or 401.
        val isExpired = accessToken.isEmpty() || (currentSecret.expiresAtMs != null && nowMs >= currentSecret.expiresAtMs!!)
        var updatedSecret: AccountSecret? = null
        if (isExpired && refreshToken.isNotEmpty()) {
            currentSecret = performRefresh(currentSecret)
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
        }

        val apiBase = overrides.resolve("claude", "https://api.anthropic.com")
        var resp = callUsage(apiBase, accessToken)
        if ((resp.statusCode == 401 || resp.statusCode == 403) && currentSecret.refreshToken.isNotBlank() && updatedSecret == null) {
            currentSecret = performRefresh(currentSecret)
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
            resp = callUsage(apiBase, accessToken)
        }

        if (resp.statusCode == 429) {
            throw ProviderProbeException(
                message = "Claude がレート制限中です (HTTP 429)。時間を置いて再試行してください。",
                isAuthError = false,
                retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(resp.firstHeader("Retry-After"), 15 * 60_000L),
                statusCode = 429,
            )
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Claude のセッションが期限切れです。認証JSONを再貼付してください。", isAuthError = true, statusCode = resp.statusCode)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Claude の取得に失敗 (HTTP ${resp.statusCode})", statusCode = resp.statusCode)
        }

        val data = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Claude のレスポンス解析に失敗")

        val profilePlan = runCatching { fetchPlan(apiBase, accessToken) }.getOrNull()
        val lines = mutableListOf<MetricLine>()

        data.objOrNull("five_hour")?.let { five ->
            val util = five.doubleOrNull("utilization")
            if (util != null) {
                val resetsMs = ProviderHttpSupport.parseDateMs(five["resets_at"])
                val effectiveUsed = if (resetsMs != null && resetsMs <= nowMs) 0.0 else util.coerceIn(0.0, 100.0)
                lines += MetricLine.Progress(
                    label = "Session (5h)",
                    used = effectiveUsed,
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = if (resetsMs != null && resetsMs > nowMs) resetsMs else null,
                    periodDurationMs = 5L * 3600_000L,
                    primaryOrder = 1,
                )
            }
        }

        data.objOrNull("seven_day")?.let { seven ->
            val util = seven.doubleOrNull("utilization")
            if (util != null) {
                val resetsMs = ProviderHttpSupport.parseDateMs(seven["resets_at"])
                val effectiveUsed = if (resetsMs != null && resetsMs <= nowMs) 0.0 else util.coerceIn(0.0, 100.0)
                lines += MetricLine.Progress(
                    label = "Weekly (7d)",
                    used = effectiveUsed,
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = if (resetsMs != null && resetsMs > nowMs) resetsMs else null,
                    periodDurationMs = 7L * 24L * 3600_000L,
                    primaryOrder = 2,
                    period = "weekly",
                )
            }
        }

        val limitsArr = data.arrOrNull("limits")
        if (limitsArr != null) {
            for (el in limitsArr) {
                val entry = el as? JsonObject ?: continue
                val name = entry.strOrNull("model_name") ?: entry.strOrNull("name") ?: continue
                val pct = entry.doubleOrNull("percent") ?: entry.doubleOrNull("utilization") ?: continue
                val resetsMs = ProviderHttpSupport.parseDateMs(entry["resets_at"])
                val effectivePct = if (resetsMs != null && resetsMs <= nowMs) 0.0 else pct.coerceIn(0.0, 100.0)
                lines += MetricLine.Progress(
                    label = "$name (7d)",
                    used = effectivePct,
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = if (resetsMs != null && resetsMs > nowMs) resetsMs else null,
                    periodDurationMs = 7L * 24L * 3600_000L,
                    period = "weekly",
                    scope = "overview",
                )
            }
        } else {
            data.objOrNull("seven_day_sonnet")?.doubleOrNull("utilization")?.let { util ->
                val resetsMs = ProviderHttpSupport.parseDateMs(data.objOrNull("seven_day_sonnet")?.get("resets_at"))
                lines += MetricLine.Progress(
                    label = "Sonnet (7d)",
                    used = if (resetsMs != null && resetsMs <= nowMs) 0.0 else util.coerceIn(0.0, 100.0),
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = if (resetsMs != null && resetsMs > nowMs) resetsMs else null,
                    periodDurationMs = 7L * 24L * 3600_000L,
                    period = "weekly",
                )
            }
        }

        data.objOrNull("extra_usage")?.let { extra ->
            if (extra.boolOrNull("is_enabled") == true) {
                val usedCents = extra.doubleOrNull("used_credits") ?: 0.0
                val limitCents = extra.doubleOrNull("monthly_limit") ?: 0.0
                if (limitCents > 0.0) {
                    lines += MetricLine.Progress(
                        label = "Extra usage",
                        used = max(0.0, usedCents / 100.0),
                        limit = limitCents / 100.0,
                        format = MetricFormat.Dollars,
                        scope = "detail",
                    )
                }
            }
        }

        if (lines.isEmpty()) throw ProviderProbeException("Claude の利用枠データが見つかりません")
        return ProbeResult(plan = profilePlan, lines = lines, updatedSecret = updatedSecret)
    }

    private suspend fun callUsage(base: String, token: String) = ProviderHttpSupport.get(
        http,
        "$base/api/oauth/usage",
        mapOf(
            "Authorization" to "Bearer $token",
            "anthropic-beta" to "oauth-2025-04-20",
            "User-Agent" to "claude-cli/2.1.294 (external, cli)",
            "Accept" to "application/json",
        ),
    )

    private suspend fun fetchPlan(base: String, token: String): String? {
        val resp = ProviderHttpSupport.get(
            http,
            "$base/api/oauth/profile",
            mapOf(
                "Authorization" to "Bearer $token",
                "anthropic-beta" to "oauth-2025-04-20",
                "User-Agent" to "claude-cli/2.1.294 (external, cli)",
                "Accept" to "application/json",
            ),
        )
        if (resp.statusCode !in 200..299) return null
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: return null
        val org = obj.objOrNull("organization") ?: return null
        return org.strOrNull("plan_display_name")
            ?: org.strOrNull("rate_limit_tier")
            ?: org.strOrNull("organization_type")
    }

    private suspend fun performRefresh(secret: AccountSecret): AccountSecret {
        val refreshBase = overrides.resolve("claude-oauth", "https://platform.claude.com")
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", secret.refreshToken)
            put("client_id", CLIENT_ID)
            put("scope", SCOPE)
        }.toString()
        val resp = ProviderHttpSupport.postJson(
            http,
            "$refreshBase/v1/oauth/token",
            body,
            mapOf("Accept" to "application/json", "User-Agent" to "claude-cli/2.1.294 (external, cli)"),
        )
        if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Claude のセッション更新に失敗。再度認証JSONを貼り付けてください。", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Claude トークン更新エラー (HTTP ${resp.statusCode})")
        }
        val parsed = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Claude トークン更新レスポンスが不正です")
        val newAccess = parsed.strOrNull("access_token")
            ?: throw ProviderProbeException("Claude トークン更新レスポンスに access_token がありません")
        val newRefresh = parsed.strOrNull("refresh_token") ?: secret.refreshToken
        val expiresIn = parsed.doubleOrNull("expires_in")?.toLong()
        val newExp = expiresIn?.let { System.currentTimeMillis() + it * 1000L }
            ?: CredentialInputParser.decodeJwtExpiryMs(newAccess)
        return secret.copy(accessToken = newAccess, refreshToken = newRefresh, expiresAtMs = newExp)
    }

    companion object {
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val SCOPE = "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
    }
}

class CodexClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var currentSecret = secret
        var accessToken = secret.accessToken.trim()
        val refreshToken = secret.refreshToken.trim()
        if (accessToken.isEmpty() && refreshToken.isEmpty()) {
            throw ProviderProbeException("Codex の認証トークンが未設定です", isAuthError = true)
        }

        val expMs = currentSecret.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(accessToken)
        var updatedSecret: AccountSecret? = null
        if ((accessToken.isEmpty() || (expMs != null && nowMs >= expMs - 60_000L)) && refreshToken.isNotEmpty()) {
            currentSecret = refreshCodex(currentSecret)
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
        }

        val base = overrides.resolve("codex", "https://chatgpt.com")
        var resp = callUsage(base, accessToken, currentSecret.accountId)
        if ((resp.statusCode == 401 || resp.statusCode == 403) && currentSecret.refreshToken.isNotBlank() && updatedSecret == null) {
            currentSecret = refreshCodex(currentSecret)
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
            resp = callUsage(base, accessToken, currentSecret.accountId)
        }

        if (resp.statusCode == 429) {
            throw ProviderProbeException(
                "Codex がレート制限中 (HTTP 429)",
                retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(resp.firstHeader("Retry-After")),
                statusCode = 429,
            )
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Codex の認証が期限切れです。auth.json を再貼付してください。", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Codex の取得に失敗 (HTTP ${resp.statusCode})", statusCode = resp.statusCode)
        }

        val data = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Codex のレスポンス解析に失敗")

        val lines = mutableListOf<MetricLine>()
        val rateLimit = data.objOrNull("rate_limit")
        val primary = rateLimit?.objOrNull("primary_window")
        val secondary = rateLimit?.objOrNull("secondary_window")

        val primaryHeaderPct = resp.firstHeader("x-codex-primary-used-percent")?.toDoubleOrNull()
        val secondaryHeaderPct = resp.firstHeader("x-codex-secondary-used-percent")?.toDoubleOrNull()

        fun addWindowLine(window: JsonObject?, headerPct: Double?, label: String, defaultPeriodMs: Long, order: Int?, isWeekly: Boolean) {
            val pct = headerPct ?: window?.doubleOrNull("used_percent") ?: return
            val periodSec = window?.doubleOrNull("limit_window_seconds")?.toLong()
            val periodMs = periodSec?.times(1000L) ?: defaultPeriodMs
            val resetAt = window?.get("reset_at")?.let { ProviderHttpSupport.parseDateMs(it) }
                ?: window?.doubleOrNull("reset_after_seconds")?.let { nowMs + (it * 1000.0).toLong() }
            lines += MetricLine.Progress(
                label = label,
                used = pct.coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = resetAt,
                periodDurationMs = periodMs,
                primaryOrder = order,
                period = if (isWeekly) "weekly" else null,
            )
        }

        addWindowLine(primary, primaryHeaderPct, "Session (5h)", 5L * 3600_000L, 1, isWeekly = false)
        addWindowLine(secondary, secondaryHeaderPct, "Weekly (7d)", 7L * 24L * 3600_000L, 2, isWeekly = true)

        if (rateLimit == null) {
            pushSpendControlLine(lines, nowMs, data)
        }

        data.objOrNull("code_review_rate_limit")?.objOrNull("primary_window")?.let { rev ->
            addWindowLine(rev, null, "Code Review", 7L * 24L * 3600_000L, null, isWeekly = true)
        }

        data.arrOrNull("additional_rate_limits")?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val name = obj.strOrNull("limit_name") ?: "追加枠"
            val w = obj.objOrNull("rate_limit")?.objOrNull("primary_window")
            addWindowLine(w, null, name, 5L * 3600_000L, null, isWeekly = false)
        }

        data.objOrNull("credits")?.doubleOrNull("balance")?.let { bal ->
            val usd = bal * 0.04
            lines += MetricLine.Text("クレジット残高", String.format(java.util.Locale.US, "$%.2f", usd), scope = "detail")
        }

        data.objOrNull("rate_limit_reset_credits")?.doubleOrNull("available_count")?.let { cnt ->
            if (cnt > 0.0) {
                lines += MetricLine.Text("リセットクレジット", "${cnt.toLong()} 回", scope = "detail")
            }
        }

        if (lines.isEmpty()) throw ProviderProbeException("Codex の利用状況データが見つかりません")
        return ProbeResult(plan = data.strOrNull("plan_type"), lines = lines, updatedSecret = updatedSecret)
    }

    private fun pushSpendControlLine(lines: MutableList<MetricLine>, nowMs: Long, data: JsonObject) {
        val ind = data.objOrNull("spend_control")?.objOrNull("individual_limit") ?: return
        var usedPct = ind.doubleOrNull("used_percent")
        if (usedPct == null) {
            val spent = ind.doubleOrNull("used")
            val cap = ind.doubleOrNull("limit")
            if (spent != null && cap != null && cap > 0.0) {
                usedPct = (spent / cap) * 100.0
            }
        }
        if (usedPct == null) return
        val resetAt = ind["reset_at"]?.let { ProviderHttpSupport.parseDateMs(it) }
            ?: ind.doubleOrNull("reset_after_seconds")?.let { nowMs + (it * 1000.0).toLong() }
        lines += MetricLine.Progress(
            label = "Monthly",
            used = usedPct.coerceIn(0.0, 100.0),
            limit = 100.0,
            format = MetricFormat.Percent,
            resetsAtMs = resetAt,
            periodDurationMs = 30L * 24L * 3600_000L,
            primaryOrder = 1,
            period = "monthly",
        )
    }

    private suspend fun callUsage(base: String, token: String, accountId: String) = ProviderHttpSupport.get(
        http,
        "$base/backend-api/wham/usage",
        buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            if (accountId.isNotBlank()) put("ChatGPT-Account-Id", accountId)
        },
    )

    private suspend fun refreshCodex(secret: AccountSecret): AccountSecret {
        val authBase = overrides.resolve("codex-oauth", "https://auth.openai.com")
        val form = "grant_type=refresh_token&client_id=$CLIENT_ID&refresh_token=${URLEncoder.encode(secret.refreshToken, "UTF-8")}"
        val resp = ProviderHttpSupport.postForm(http, "$authBase/oauth/token", form)
        if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Codex トークン更新に失敗。auth.json を再貼付してください。", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Codex トークン更新エラー (HTTP ${resp.statusCode})")
        }
        val parsed = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Codex 更新レスポンス解析に失敗")
        val newAccess = parsed.strOrNull("access_token")
            ?: throw ProviderProbeException("Codex 更新レスポンスに access_token がありません")
        val newRefresh = parsed.strOrNull("refresh_token") ?: secret.refreshToken
        val exp = parsed.doubleOrNull("expires_in")?.toLong()?.let { System.currentTimeMillis() + it * 1000L }
            ?: CredentialInputParser.decodeJwtExpiryMs(newAccess)
        return secret.copy(accessToken = newAccess, refreshToken = newRefresh, expiresAtMs = exp)
    }

    companion object {
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    }
}
