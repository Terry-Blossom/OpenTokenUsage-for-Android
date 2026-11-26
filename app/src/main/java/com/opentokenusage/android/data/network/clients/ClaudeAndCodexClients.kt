package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.boolOrNull
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.data.storage.CachedClaudeResetGrants
import com.opentokenusage.android.data.storage.ClaudeResetGrantsCache
import com.opentokenusage.android.data.storage.InMemoryClaudeResetGrantsCache
import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ResetCreditItem
import com.opentokenusage.android.domain.model.groupResetCreditItems
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlin.math.max

class ClaudeClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
    private val resetGrantsCache: ClaudeResetGrantsCache = InMemoryClaudeResetGrantsCache(),
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

        return try {
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

            val atLimit = lines.filterIsInstance<MetricLine.Progress>().any { it.usedFraction >= 1.0 }
            val resetItems = resolveResetGrants(
                accountId = account.id,
                apiBase = apiBase,
                accessToken = accessToken,
                inlineCedarEmber = data.objOrNull("cedar_ember"),
                atLimit = atLimit,
                nowMs = nowMs,
            )
            if (resetItems.isNotEmpty()) {
                lines += MetricLine.ResetCredit(
                    label = "リセットクレジット",
                    availableCount = resetItems.sumOf { it.count },
                    items = resetItems,
                    expiresAtMs = resetItems.mapNotNull { it.expiresAtMs }.minOrNull(),
                    scope = "detail",
                )
            }

            if (lines.isEmpty()) throw ProviderProbeException("Claude の利用枠データが見つかりません")
            ProbeResult(plan = profilePlan, lines = lines, updatedSecret = updatedSecret)
        } catch (e: ProviderProbeException) {
            throw e.withUpdatedSecret(updatedSecret)
        }
    }

    private suspend fun resolveResetGrants(
        accountId: String,
        apiBase: String,
        accessToken: String,
        inlineCedarEmber: JsonObject?,
        atLimit: Boolean,
        nowMs: Long,
    ): List<ResetCreditItem> {
        if (inlineCedarEmber != null) {
            val parsed = parseCedarEmberItems(inlineCedarEmber, nowMs)
            val eligible = inlineCedarEmber.boolOrNull("eligible") == true
            resetGrantsCache.save(
                accountId,
                CachedClaudeResetGrants(checkedAtMs = nowMs, eligible = eligible, items = parsed),
            )
            return groupResetCreditItems(parsed)
        }

        val cached = resetGrantsCache.load(accountId)
        val intervalMs = if (cached?.eligible == true || atLimit) {
            RESET_GRANTS_INTERVAL_MS
        } else {
            NO_GRANTS_INTERVAL_MS
        }
        if (cached != null && nowMs - cached.checkedAtMs < intervalMs) {
            val active = cached.items.filter { it.count > 0 && (it.expiresAtMs == null || it.expiresAtMs > nowMs) }
            return groupResetCreditItems(active)
        }

        var eligible = cached?.eligible ?: false
        var items = cached?.items.orEmpty()
        val resp = runCatching { callResetGrants(apiBase, accessToken) }.getOrNull()
        val parsedObj = resp
            ?.takeIf { it.statusCode in 200..299 }
            ?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
        if (parsedObj != null) {
            val block = parsedObj.objOrNull("cedar_ember")
            if (block != null) {
                eligible = block.boolOrNull("eligible") == true
                items = parseCedarEmberItems(block, nowMs)
            } else {
                eligible = false
                items = emptyList()
            }
        }

        resetGrantsCache.save(
            accountId,
            CachedClaudeResetGrants(checkedAtMs = nowMs, eligible = eligible, items = items),
        )
        val active = items.filter { it.count > 0 && (it.expiresAtMs == null || it.expiresAtMs > nowMs) }
        return groupResetCreditItems(active)
    }

    private fun parseCedarEmberItems(block: JsonObject, nowMs: Long): List<ResetCreditItem> {
        if (block.boolOrNull("eligible") != true) return emptyList()
        val grants = block.arrOrNull("grants") ?: return emptyList()
        val result = mutableListOf<ResetCreditItem>()
        for (el in grants) {
            val grant = el as? JsonObject ?: continue
            val left = grant.doubleOrNull("resets_left") ?: continue
            if (left < 1.0) continue
            if (grant.boolOrNull("paused") == true) continue
            if (grant.boolOrNull("usable_now") == false) continue
            val endsAtMs = ProviderHttpSupport.parseDateMs(grant["ends_at"])
            if (endsAtMs != null && endsAtMs <= nowMs) continue
            result += ResetCreditItem(
                count = left.toInt(),
                expiresAtMs = endsAtMs,
                kind = resolveGrantKind(grant),
            )
        }
        return result
    }

    private fun resolveGrantKind(grant: JsonObject): String? {
        if (grant.boolOrNull("gives_back_weekly") == true) return "fable"
        val rawType = grant.strOrNull("type")
        if (rawType == "session" || rawType == "weekly" || rawType == "full") return rawType
        val clears = grant.arrOrNull("clears")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        if (clears.isEmpty()) return null
        val session = "five_hour" in clears
        val other = clears.any { it != "five_hour" }
        return when {
            session && !other -> "session"
            other && !session -> "weekly"
            else -> "full"
        }
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

    private suspend fun callResetGrants(base: String, token: String) = ProviderHttpSupport.get(
        http,
        "$base/api/oauth/usage?cedar_ember=1&skip_spend=1",
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
        private const val RESET_GRANTS_INTERVAL_MS = 60L * 60_000L
        private const val NO_GRANTS_INTERVAL_MS = 24L * 60L * 60_000L
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

        return try {
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
                val summaryCount = cnt.toInt()
                if (summaryCount > 0) {
                    val resetItems = fetchCodexResetCredits(base, accessToken, currentSecret.accountId, summaryCount, nowMs)
                    val activeCount = resetItems.sumOf { it.count }
                    if (activeCount > 0) {
                        lines += MetricLine.ResetCredit(
                            label = "リセットクレジット",
                            availableCount = activeCount,
                            items = resetItems,
                            expiresAtMs = resetItems.mapNotNull { it.expiresAtMs }.minOrNull(),
                            scope = "detail",
                        )
                    }
                }
            }

            if (lines.isEmpty()) throw ProviderProbeException("Codex の利用状況データが見つかりません")
            ProbeResult(plan = data.strOrNull("plan_type"), lines = lines, updatedSecret = updatedSecret)
        } catch (e: ProviderProbeException) {
            throw e.withUpdatedSecret(updatedSecret)
        }
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

    private suspend fun fetchCodexResetCredits(
        base: String,
        token: String,
        accountId: String,
        summaryCount: Int,
        nowMs: Long,
    ): List<ResetCreditItem> {
        val resp = runCatching {
            ProviderHttpSupport.get(
                http,
                "$base/backend-api/wham/rate-limit-reset-credits",
                buildMap {
                    put("Authorization", "Bearer $token")
                    put("Accept", "application/json")
                    if (accountId.isNotBlank()) put("ChatGPT-Account-Id", accountId)
                },
            )
        }.getOrNull()
        val body = resp
            ?.takeIf { it.statusCode in 200..299 }
            ?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
        val creditsArr = body?.arrOrNull("credits")
        if (body == null || creditsArr == null) {
            return listOf(ResetCreditItem(count = summaryCount, expiresAtMs = null))
        }

        val rawAvailable = mutableListOf<ResetCreditItem>()
        for (el in creditsArr) {
            val credit = el as? JsonObject ?: continue
            if (credit.strOrNull("status") != "available") continue
            val expiresAtMs = ProviderHttpSupport.parseDateMs(credit["expires_at"])
            rawAvailable += ResetCreditItem(count = 1, expiresAtMs = expiresAtMs)
        }
        val knownCount = rawAvailable.size
        val total = body.doubleOrNull("available_count")?.toInt() ?: summaryCount
        val active = rawAvailable.filter { it.expiresAtMs == null || it.expiresAtMs > nowMs }.toMutableList()
        if (total > knownCount) {
            active += ResetCreditItem(count = total - knownCount, expiresAtMs = null)
        }
        return groupResetCreditItems(active)
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
