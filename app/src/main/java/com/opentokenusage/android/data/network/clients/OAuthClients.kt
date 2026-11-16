package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.auth.AntigravityOAuthClient
import com.opentokenusage.android.data.auth.AntigravityOAuthConfig
import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.boolOrNull
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.longOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.round

class KimiClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var cur = secret
        var access = cur.accessToken.trim()
        val refresh = cur.refreshToken.trim()
        if (access.isEmpty() && refresh.isEmpty()) {
            throw ProviderProbeException("Kimi Code の認証トークンが未設定です", isAuthError = true)
        }
        var updated: AccountSecret? = null
        val exp = cur.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(access)
        if ((access.isEmpty() || (exp != null && nowMs >= exp - 60_000L)) && refresh.isNotEmpty()) {
            cur = refreshKimi(cur)
            updated = cur
            access = cur.accessToken
        }

        val base = overrides.resolve("kimi", "https://api.kimi.com")
        var resp = ProviderHttpSupport.get(
            http,
            "$base/coding/v1/usages",
            mapOf("Authorization" to "Bearer $access", "Accept" to "application/json", "User-Agent" to "OpenTokenUsage"),
        )
        if ((resp.statusCode == 401 || resp.statusCode == 403) && cur.refreshToken.isNotBlank() && updated == null) {
            cur = refreshKimi(cur)
            updated = cur
            access = cur.accessToken
            resp = ProviderHttpSupport.get(
                http,
                "$base/coding/v1/usages",
                mapOf("Authorization" to "Bearer $access", "Accept" to "application/json", "User-Agent" to "OpenTokenUsage"),
            )
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Kimi Code のセッションが期限切れです", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Kimi Code の取得に失敗 (HTTP ${resp.statusCode})")
        }
        val data = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Kimi Code のレスポンス解析に失敗")

        val lines = mutableListOf<MetricLine>()
        data.arrOrNull("limits")?.forEachIndexed { idx, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val detail = obj.objOrNull("detail") ?: obj
            val win = obj.objOrNull("window")
            val periodMs = parseKimiPeriodMs(win)
            val label = when {
                periodMs != null && periodMs <= 6L * 3600_000L -> "5時間枠"
                periodMs != null && periodMs >= 6L * 24L * 3600_000L -> "Weekly"
                else -> "利用枠 ${idx + 1}"
            }
            parseKimiRow(detail, label, periodMs, idx + 1)?.let { lines += it }
        }
        data.objOrNull("usage")?.let { u ->
            parseKimiRow(u, "月間合計", 30L * 24L * 3600_000L, if (lines.isEmpty()) 1 else null)?.let { lines += it }
        }

        if (lines.isEmpty()) throw ProviderProbeException("Kimi Code のクォータ情報が見つかりません")
        return ProbeResult(plan = data.objOrNull("user")?.strOrNull("membership") ?: "Kimi Code", lines = lines, updatedSecret = updated)
    }

    private fun parseKimiRow(row: JsonObject, label: String, periodMs: Long?, order: Int?): MetricLine.Progress? {
        val limit = row.doubleOrNull("limit") ?: return null
        if (limit <= 0.0) return null
        val used = row.doubleOrNull("used")
            ?: row.doubleOrNull("remaining")?.let { max(0.0, limit - it) }
            ?: return null
        return MetricLine.Progress(
            label = label,
            used = used.coerceIn(0.0, limit),
            limit = limit,
            format = if (limit == 100.0) MetricFormat.Percent else MetricFormat.Count(""),
            resetsAtMs = ProviderHttpSupport.parseDateMs(row["resetTime"] ?: row["reset_time"]),
            periodDurationMs = periodMs,
            primaryOrder = order,
            period = if (periodMs != null && kotlin.math.abs(periodMs - 7L * 24L * 3600_000L) < 24L * 3600_000L) "weekly" else null,
        )
    }

    private fun parseKimiPeriodMs(win: JsonObject?): Long? {
        if (win == null) return null
        val dur = win.doubleOrNull("duration")?.toLong() ?: return null
        if (dur <= 0L) return null
        val unit = (win.strOrNull("timeUnit") ?: win.strOrNull("time_unit")).orEmpty().uppercase()
        return when {
            "MINUTE" in unit -> dur * 60_000L
            "HOUR" in unit -> dur * 3600_000L
            "DAY" in unit -> dur * 24L * 3600_000L
            "SECOND" in unit -> dur * 1000L
            else -> null
        }
    }

    private suspend fun refreshKimi(secret: AccountSecret): AccountSecret {
        val authBase = overrides.resolve("kimi-oauth", "https://auth.kimi.com")
        val form = "grant_type=refresh_token&client_id=17e5f671-d194-4dfb-9706-5516cb48c098&refresh_token=${URLEncoder.encode(secret.refreshToken, "UTF-8")}"
        val resp = ProviderHttpSupport.postForm(http, "$authBase/api/oauth/token", form)
        if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Kimi Code のセッションが期限切れです", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Kimi 更新エラー (HTTP ${resp.statusCode})")
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Kimi 更新レスポンスが不正です")
        val access = obj.strOrNull("access_token") ?: throw ProviderProbeException("Kimi access_token 欠落")
        val refresh = obj.strOrNull("refresh_token") ?: secret.refreshToken
        val exp = obj.doubleOrNull("expires_in")?.toLong()?.let { System.currentTimeMillis() + it * 1000L }
            ?: CredentialInputParser.decodeJwtExpiryMs(access)
        return secret.copy(accessToken = access, refreshToken = refresh, expiresAtMs = exp)
    }
}

class GrokClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val cursorApiKey = secret.apiKey.trim().takeIf { it.startsWith("crsr_") }
        if (cursorApiKey != null) return probeCursorGrokBot(secret, cursorApiKey, nowMs)

        var cur = secret
        var access = cur.accessToken.trim()
        val refresh = cur.refreshToken.trim()
        if (access.isEmpty() && refresh.isEmpty()) {
            throw ProviderProbeException("Grok の認証トークンが未設定です", isAuthError = true)
        }
        var updated: AccountSecret? = null
        val exp = cur.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(access)
        if ((access.isEmpty() || (exp != null && nowMs >= exp - 60_000L)) && refresh.isNotEmpty()) {
            cur = refreshGrok(cur)
            updated = cur
            access = cur.accessToken
        }

        val base = overrides.resolve("grok", "https://cli-chat-proxy.grok.com")
        val headers = mapOf(
            "Authorization" to "Bearer $access",
            "X-XAI-Token-Auth" to "xai-grok-cli",
            "Accept" to "application/json",
        )
        var resp = ProviderHttpSupport.get(http, "$base/v1/billing?format=credits", headers)
        if ((resp.statusCode == 401 || resp.statusCode == 403) && cur.refreshToken.isNotBlank() && updated == null) {
            cur = refreshGrok(cur)
            updated = cur
            access = cur.accessToken
            resp = ProviderHttpSupport.get(
                http,
                "$base/v1/billing?format=credits",
                headers + ("Authorization" to "Bearer $access"),
            )
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Grok のセッションが期限切れです", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Grok 取得失敗 (HTTP ${resp.statusCode})")
        val data = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Grok レスポンス解析失敗")

        val lines = mutableListOf<MetricLine>()
        val rem = data.doubleOrNull("remaining_credits") ?: data.doubleOrNull("remainingCredits")
        val total = data.doubleOrNull("total_credits") ?: data.doubleOrNull("totalCredits")
        if (total != null && total > 0.0 && rem != null) {
            lines += MetricLine.Progress(
                label = "クレジット",
                used = max(0.0, total - rem),
                limit = total,
                format = MetricFormat.Dollars,
                resetsAtMs = ProviderHttpSupport.parseDateMs(data["reset_at"] ?: data["resets_at"]),
                primaryOrder = 1,
            )
        } else {
            val pct = data.doubleOrNull("percent_used") ?: data.doubleOrNull("used_percent")
            if (pct != null) {
                lines += MetricLine.Progress(
                    label = "利用枠",
                    used = pct.coerceIn(0.0, 100.0),
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = ProviderHttpSupport.parseDateMs(data["reset_at"] ?: data["resets_at"]),
                    primaryOrder = 1,
                )
            } else if (rem != null) {
                lines += MetricLine.Text("残クレジット", String.format(java.util.Locale.US, "$%.2f", rem))
            }
        }
        val plan = data.strOrNull("tier") ?: data.strOrNull("plan")
        if (lines.isEmpty()) throw ProviderProbeException("Grok の利用情報が見つかりません")
        return ProbeResult(plan = plan, lines = lines, updatedSecret = updated)
    }

    private suspend fun probeCursorGrokBot(
        secret: AccountSecret,
        cursorApiKey: String,
        nowMs: Long,
    ): ProbeResult {
        val base = overrides.resolve("cursor", "https://api2.cursor.sh")
        var currentSecret = secret
        var accessToken = currentSecret.accessToken.trim()
        var updatedSecret: AccountSecret? = null
        val expiresAt = currentSecret.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(accessToken)
        if (accessToken.isEmpty() || (expiresAt != null && nowMs >= expiresAt - 5 * 60_000L)) {
            currentSecret = exchangeCursorCredential(
                http,
                base,
                currentSecret,
                currentSecret.refreshToken.ifBlank { cursorApiKey },
            )
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
        }

        suspend fun fetchGrokBotUsage(token: String) = ProviderHttpSupport.postJson(
            http,
            "$base/aiserver.v1.DashboardService/GetSandUsageStatus",
            "{}",
            mapOf("Authorization" to "Bearer $token", "Connect-Protocol-Version" to "1", "Accept" to "application/json"),
        )

        var response = fetchGrokBotUsage(accessToken)
        if ((response.statusCode == 401 || response.statusCode == 403) && updatedSecret == null) {
            currentSecret = exchangeCursorCredential(
                http,
                base,
                currentSecret,
                currentSecret.refreshToken.ifBlank { cursorApiKey },
            )
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
            response = fetchGrokBotUsage(accessToken)
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw ProviderProbeException("Cursor API キー/トークンの認証に失敗しました", isAuthError = true, statusCode = response.statusCode)
        }
        if (response.statusCode !in 200..299) {
            throw ProviderProbeException("Cursor Grok Bot の取得に失敗 (HTTP ${response.statusCode})", statusCode = response.statusCode)
        }
        val usage = ProviderHttpSupport.tryParseObject(response.bodyText)
            ?: throw ProviderProbeException("Cursor Grok Bot のレスポンス解析に失敗しました")
        val percent = usage.doubleOrNull("usagePercent")
            ?: throw ProviderProbeException("Cursor Grok Bot の利用率が見つかりません")
        val line = MetricLine.Progress(
            label = "Grok Bot",
            used = percent.coerceIn(0.0, 100.0),
            limit = 100.0,
            format = MetricFormat.Percent,
            resetsAtMs = ProviderHttpSupport.parseDateMs(usage["nextResetTimestampUtc"]),
            periodDurationMs = 7L * 24L * 3600_000L,
            primaryOrder = 1,
            period = "weekly",
        )
        return ProbeResult(plan = "Grok Bot", lines = listOf(line), updatedSecret = updatedSecret)
    }

    private suspend fun refreshGrok(secret: AccountSecret): AccountSecret {
        val authBase = overrides.resolve("grok-oauth", "https://auth.x.ai")
        val form = "grant_type=refresh_token&client_id=b1a00492-073a-47ea-816f-4c329264a828&refresh_token=${URLEncoder.encode(secret.refreshToken, "UTF-8")}"
        val resp = ProviderHttpSupport.postForm(http, "$authBase/oauth2/token", form)
        if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Grok セッション更新に失敗しました", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Grok 更新エラー (HTTP ${resp.statusCode})")
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Grok 更新レスポンス不正")
        val access = obj.strOrNull("access_token") ?: throw ProviderProbeException("Grok access_token 欠落")
        val refresh = obj.strOrNull("refresh_token") ?: secret.refreshToken
        val exp = obj.doubleOrNull("expires_in")?.toLong()?.let { System.currentTimeMillis() + it * 1000L }
            ?: CredentialInputParser.decodeJwtExpiryMs(access)
        return secret.copy(accessToken = access, refreshToken = refresh, expiresAtMs = exp)
    }
}

class MuseClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val token = secret.accessToken.ifBlank { secret.apiKey }.trim()
            .takeIf { it.isNotEmpty() }
            ?: throw ProviderProbeException("Muse Code のトークンが未設定です", isAuthError = true)

        val base = overrides.resolve("muse", "https://api.meta.ai")
        val resp = ProviderHttpSupport.postJson(
            http,
            "$base/muse-code/key",
            """{"onboard":false}""",
            mapOf("Authorization" to "Bearer $token", "x-api-version" to "1.0.0", "Accept" to "application/json"),
        )
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Muse Code の認証に失敗しました", isAuthError = true)
        }
        if (resp.statusCode == 429) {
            throw ProviderProbeException(
                "Muse Code がレート制限中 (HTTP 429)",
                retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(resp.firstHeader("Retry-After")),
                statusCode = 429,
            )
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Muse Code 取得失敗 (HTTP ${resp.statusCode})")
        val data = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Muse Code レスポンス解析失敗")
        val subs = data.objOrNull("subs_usage") ?: data

        val lines = mutableListOf<MetricLine>()
        fun addMuseWindow(key: String, label: String, defaultPeriodMs: Long, order: Int, isWeekly: Boolean) {
            val w = subs.objOrNull(key) ?: return
            val pctUsed = w.doubleOrNull("percent_used")
                ?: w.doubleOrNull("used_percent")
                ?: run {
                    val u = w.doubleOrNull("used")
                    val l = w.doubleOrNull("limit")
                    if (u != null && l != null && l > 0.0) (u / l) * 100.0 else null
                } ?: return
            lines += MetricLine.Progress(
                label = label,
                used = pctUsed.coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = ProviderHttpSupport.parseDateMs(w["reset_at"] ?: w["resets_at"]),
                periodDurationMs = defaultPeriodMs,
                primaryOrder = order,
                period = if (isWeekly) "weekly" else null,
            )
        }
        addMuseWindow("window", "Session", 5L * 3600_000L, 1, false)
        addMuseWindow("weekly", "Weekly", 7L * 24L * 3600_000L, 2, true)
        if (lines.isEmpty()) throw ProviderProbeException("Muse Code のクォータ情報が見つかりません")
        return ProbeResult(lines = lines)
    }
}

class FactoryClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var cur = secret
        var access = cur.accessToken.trim()
        val refresh = cur.refreshToken.trim()
        if (access.isEmpty() && refresh.isEmpty()) {
            throw ProviderProbeException("Factory のトークンが未設定です", isAuthError = true)
        }
        var updated: AccountSecret? = null
        val exp = cur.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(access)
        if ((access.isEmpty() || (exp != null && nowMs >= exp - 30 * 60_000L)) && refresh.isNotEmpty()) {
            cur = refreshFactory(cur)
            updated = cur
            access = cur.accessToken
        }

        val base = overrides.resolve("factory", "https://api.factory.ai")
        var resp = ProviderHttpSupport.postJson(
            http,
            "$base/api/organization/subscription/usage",
            """{"useCache":true}""",
            mapOf("Authorization" to "Bearer $access", "Accept" to "application/json"),
        )
        if (resp.statusCode == 405) {
            resp = ProviderHttpSupport.get(
                http,
                "$base/api/organization/subscription/usage",
                mapOf("Authorization" to "Bearer $access", "Accept" to "application/json"),
            )
        }
        if ((resp.statusCode == 401 || resp.statusCode == 403) && cur.refreshToken.isNotBlank() && updated == null) {
            cur = refreshFactory(cur)
            updated = cur
            access = cur.accessToken
            resp = ProviderHttpSupport.postJson(
                http,
                "$base/api/organization/subscription/usage",
                """{"useCache":true}""",
                mapOf("Authorization" to "Bearer $access", "Accept" to "application/json"),
            )
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Factory のセッションが期限切れです", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Factory 取得失敗 (HTTP ${resp.statusCode})")
        val usage = ProviderHttpSupport.tryParseObject(resp.bodyText)?.objOrNull("usage")
            ?: throw ProviderProbeException("Factory の利用情報が見つかりません")

        val startMs = ProviderHttpSupport.parseDateMs(usage["startDate"])
        val endMs = ProviderHttpSupport.parseDateMs(usage["endDate"])
        val periodMs = if (startMs != null && endMs != null && endMs > startMs) endMs - startMs else 30L * 24L * 3600_000L

        val lines = mutableListOf<MetricLine>()
        usage.objOrNull("standard")?.let { std ->
            val used = std.doubleOrNull("orgTotalTokensUsed") ?: 0.0
            val allow = std.doubleOrNull("totalAllowance") ?: std.doubleOrNull("basicAllowance") ?: 0.0
            if (allow > 0.0) {
                val pct = ((used / allow) * 100.0).coerceIn(0.0, 100.0)
                lines += MetricLine.Progress("Standard", pct, 100.0, MetricFormat.Percent, endMs, periodMs, primaryOrder = 1)
            }
        }
        usage.objOrNull("premium")?.let { prem ->
            val used = prem.doubleOrNull("orgTotalTokensUsed") ?: 0.0
            val allow = prem.doubleOrNull("totalAllowance") ?: prem.doubleOrNull("basicAllowance") ?: 0.0
            if (allow > 0.0) {
                val pct = ((used / allow) * 100.0).coerceIn(0.0, 100.0)
                lines += MetricLine.Progress("Premium", pct, 100.0, MetricFormat.Percent, endMs, periodMs, primaryOrder = 2)
            }
        }
        if (lines.isEmpty()) lines += MetricLine.Badge("ステータス", "利用データなし")
        return ProbeResult(lines = lines, updatedSecret = updated)
    }

    private suspend fun refreshFactory(secret: AccountSecret): AccountSecret {
        val workosBase = overrides.resolve("factory-oauth", "https://api.workos.com")
        val form = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(secret.refreshToken, "UTF-8")}&client_id=client_01HNM792M5G5G1A2THWPXKFMXB"
        val resp = ProviderHttpSupport.postForm(http, "$workosBase/user_management/authenticate", form)
        if (resp.statusCode == 400 || resp.statusCode == 401) {
            throw ProviderProbeException("Factory セッション期限切れ。再度認証情報を貼り付けてください。", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Factory 更新失敗 (HTTP ${resp.statusCode})")
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Factory 更新レスポンス不正")
        val access = obj.strOrNull("access_token") ?: throw ProviderProbeException("Factory access_token 欠落")
        val refresh = obj.strOrNull("refresh_token") ?: secret.refreshToken
        return secret.copy(accessToken = access, refreshToken = refresh, expiresAtMs = CredentialInputParser.decodeJwtExpiryMs(access))
    }
}

class KiroClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var cur = secret
        var access = cur.accessToken.trim()
        val refresh = cur.refreshToken.trim()
        if (access.isEmpty() && refresh.isEmpty()) {
            throw ProviderProbeException("Kiro のトークンが未設定です", isAuthError = true)
        }
        var updated: AccountSecret? = null
        val exp = cur.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(access)
        if ((access.isEmpty() || (exp != null && nowMs >= exp - 60_000L)) && refresh.isNotEmpty()) {
            cur = refreshKiro(cur)
            updated = cur
            access = cur.accessToken
        }

        val arn = cur.profileArn.trim()
        val region = arn.split(':').getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: cur.awsRegion.ifBlank { "us-east-1" }
        val base = overrides.resolve("kiro", "https://q.$region.amazonaws.com")
        val query = buildString {
            append("$base/getUsageLimits?origin=${URLEncoder.encode("AI_EDITOR", "UTF-8")}")
            if (arn.isNotBlank()) append("&profileArn=${URLEncoder.encode(arn, "UTF-8")}")
            append("&resourceType=${URLEncoder.encode("AGENTIC_REQUEST", "UTF-8")}")
        }

        var resp = ProviderHttpSupport.get(http, query, mapOf("Authorization" to "Bearer $access", "Accept" to "application/json"))
        if ((resp.statusCode == 401 || resp.statusCode == 403) && cur.refreshToken.isNotBlank() && updated == null) {
            cur = refreshKiro(cur)
            updated = cur
            access = cur.accessToken
            resp = ProviderHttpSupport.get(http, query, mapOf("Authorization" to "Bearer $access", "Accept" to "application/json"))
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Kiro のセッションが期限切れです", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Kiro 取得失敗 (HTTP ${resp.statusCode})")
        val data = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Kiro レスポンス解析失敗")

        val lines = mutableListOf<MetricLine>()
        val breakdowns = data.arrOrNull("usageBreakdownList")
        breakdowns?.forEachIndexed { idx, el ->
            val raw = el as? JsonObject ?: return@forEachIndexed
            val used = raw.doubleOrNull("currentUsageWithPrecision") ?: raw.doubleOrNull("currentUsage") ?: return@forEachIndexed
            val limit = raw.doubleOrNull("usageLimitWithPrecision") ?: raw.doubleOrNull("usageLimit") ?: return@forEachIndexed
            if (limit <= 0.0) return@forEachIndexed
            val label = raw.strOrNull("displayName") ?: raw.strOrNull("resourceType")?.lowercase()?.replace('_', ' ') ?: "Credits"
            lines += MetricLine.Progress(
                label = label,
                used = used.coerceAtLeast(0.0),
                limit = limit,
                format = MetricFormat.Count("cr"),
                resetsAtMs = ProviderHttpSupport.parseDateMs(raw["nextDateReset"] ?: raw["resetDate"]),
                periodDurationMs = 30L * 24L * 3600_000L,
                primaryOrder = if (idx == 0) 1 else null,
            )
            raw.objOrNull("freeTrialInfo")?.let { trial ->
                if (trial.strOrNull("freeTrialStatus") == "ACTIVE") {
                    val tUsed = trial.doubleOrNull("currentUsageWithPrecision") ?: trial.doubleOrNull("currentUsage")
                    val tLim = trial.doubleOrNull("usageLimitWithPrecision") ?: trial.doubleOrNull("usageLimit")
                    if (tUsed != null && tLim != null && tLim > 0.0) {
                        lines += MetricLine.Progress(
                            label = "Free Trial",
                            used = tUsed,
                            limit = tLim,
                            format = MetricFormat.Count("cr"),
                            resetsAtMs = ProviderHttpSupport.parseDateMs(trial["freeTrialExpiry"]),
                            scope = "detail",
                        )
                    }
                }
            }
        }
        val plan = data.objOrNull("subscriptionInfo")?.strOrNull("subscriptionTitle")
        if (lines.isEmpty()) throw ProviderProbeException("Kiro クォータ情報が取得できませんでした")
        return ProbeResult(plan = plan, lines = lines, updatedSecret = updated)
    }

    private suspend fun refreshKiro(secret: AccountSecret): AccountSecret {
        val refreshUrl = overrides.resolve("kiro-oauth", "https://prod.us-east-1.auth.desktop.kiro.dev") + "/refreshToken"
        val body = """{"refreshToken":"${secret.refreshToken}"}"""
        val resp = ProviderHttpSupport.postJson(http, refreshUrl, body)
        if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Kiro セッション更新に失敗", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Kiro 更新エラー (HTTP ${resp.statusCode})")
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Kiro 更新レスポンス不正")
        val access = obj.strOrNull("accessToken") ?: throw ProviderProbeException("Kiro accessToken 欠落")
        val refresh = obj.strOrNull("refreshToken") ?: secret.refreshToken
        val exp = obj.doubleOrNull("expiresIn")?.toLong()?.let { System.currentTimeMillis() + it * 1000L }
            ?: CredentialInputParser.decodeJwtExpiryMs(access)
        return secret.copy(accessToken = access, refreshToken = refresh, expiresAtMs = exp)
    }
}

class AntigravityClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
    private val oauthClientIdProvider: () -> String = AntigravityOAuthConfig::resolveClientId,
    private val oauthClientSecretProvider: () -> String = AntigravityOAuthConfig::resolveClientSecret,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var cur = secret
        var access = cur.accessToken.trim()
        val refresh = cur.refreshToken.trim()
        if (access.isEmpty() && refresh.isEmpty()) {
            throw ProviderProbeException("Antigravity の OAuth アクセストークンまたはリフレッシュトークンが未設定です", isAuthError = true)
        }
        var updated: AccountSecret? = null
        val exp = cur.expiresAtMs
        if ((access.isEmpty() || (exp != null && nowMs >= exp - 60_000L)) && refresh.isNotEmpty()) {
            cur = refreshGoogleToken(cur)
            updated = cur
            access = cur.accessToken
        }

        val dailyHost = overrides.resolve("antigravity-daily", AntigravityOAuthConfig.DEFAULT_DAILY_ENDPOINT)
        val prodHost = overrides.resolve("antigravity", AntigravityOAuthConfig.DEFAULT_PROD_ENDPOINT)
        val hosts = listOf(dailyHost, prodHost).distinct()

        val headers = mapOf(
            "Authorization" to "Bearer $access",
            "Accept" to "application/json",
            "User-Agent" to AntigravityOAuthConfig.ANTIGRAVITY_USER_AGENT,
        )
        val loadCodeAssistBody = """{"metadata":{"ideType":"ANTIGRAVITY"}}"""

        var assistResp = runCatching {
            ProviderHttpSupport.postJson(http, "$prodHost/v1internal:loadCodeAssist", loadCodeAssistBody, headers)
        }.getOrNull()
        if (assistResp != null && (assistResp.statusCode == 401 || assistResp.statusCode == 403) && refresh.isNotEmpty() && updated == null) {
            cur = refreshGoogleToken(cur)
            updated = cur
            access = cur.accessToken
            assistResp = runCatching {
                ProviderHttpSupport.postJson(http, "$prodHost/v1internal:loadCodeAssist", loadCodeAssistBody, headers + ("Authorization" to "Bearer $access"))
            }.getOrNull()
        }

        val assistJson = assistResp?.takeIf { it.statusCode in 200..299 }?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
        var projectId = AntigravityOAuthClient.extractProjectId(assistJson)
        if (projectId == null && dailyHost != prodHost) {
            val dailyAssistResp = runCatching {
                ProviderHttpSupport.postJson(
                    http,
                    "$dailyHost/v1internal:loadCodeAssist",
                    loadCodeAssistBody,
                    headers + ("Authorization" to "Bearer $access"),
                )
            }.getOrNull()
            val dailyJson = dailyAssistResp?.takeIf { it.statusCode in 200..299 }?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
            projectId = AntigravityOAuthClient.extractProjectId(dailyJson)
        }
        if (projectId == null) {
            projectId = cur.accountId.substringBefore('|').trim().takeIf { it.isNotEmpty() }
        }
        val plan = readPlanName(assistJson)

        val authHeaders = headers + ("Authorization" to "Bearer $access")
        val reqBody = if (projectId != null) """{"project":"$projectId"}""" else "{}"

        val summaryBodies = mutableListOf<JsonObject>()
        var authFailed = false
        for (h in hosts) {
            val r = runCatching { ProviderHttpSupport.postJson(http, "$h/v1internal:retrieveUserQuotaSummary", reqBody, authHeaders) }.getOrNull() ?: continue
            if (r.statusCode == 401 || r.statusCode == 403) {
                authFailed = true
                continue
            }
            if (r.statusCode in 200..299) {
                ProviderHttpSupport.tryParseObject(r.bodyText)?.let { summaryBodies += it }
            }
        }
        if (summaryBodies.isEmpty() && authFailed) {
            throw ProviderProbeException("Antigravity の Google OAuth セッションが期限切れです", isAuthError = true)
        }

        val lines = parseSummaryBuckets(summaryBodies).toMutableList()
        if (lines.isEmpty()) {
            // Fallback to legacy fetchAvailableModels
            for (h in hosts) {
                val r = runCatching { ProviderHttpSupport.postJson(http, "$h/v1internal:fetchAvailableModels", "{}", authHeaders) }.getOrNull() ?: continue
                if (r.statusCode in 200..299) {
                    val modelsObj = ProviderHttpSupport.tryParseObject(r.bodyText)?.objOrNull("models")
                    if (modelsObj != null) {
                        lines += parseLegacyModels(modelsObj)
                        break
                    }
                }
            }
        }
        if (lines.isEmpty()) throw ProviderProbeException("Antigravity のクォータ情報が取得できませんでした")
        return ProbeResult(plan = plan, lines = lines, updatedSecret = updated)
    }

    private fun parseSummaryBuckets(bodies: List<JsonObject>): List<MetricLine> {
        val specs = listOf(
            Triple("gemini-5h", "Session", 5L * 3600_000L),
            Triple("gemini-weekly", "Weekly", 7L * 24L * 3600_000L),
            Triple("3p-5h", "Claude", 5L * 3600_000L),
            Triple("3p-weekly", "Claude Weekly", 7L * 24L * 3600_000L),
        )
        val pooled = linkedMapOf<String, Pair<Double, Long?>>()
        var argonLine: MetricLine? = null

        for (body in bodies) {
            val groups = body.objOrNull("response")?.arrOrNull("groups") ?: body.arrOrNull("groups") ?: continue
            for (gEl in groups) {
                val group = gEl as? JsonObject ?: continue
                val gName = group.strOrNull("displayName")
                val buckets = group.arrOrNull("buckets") ?: continue
                if (gName == "Gemini 4 Argon") {
                    if (argonLine == null && buckets.size == 1) {
                        val b = buckets.firstOrNull() as? JsonObject
                        if (b != null && b.strOrNull("window") == "weekly" && b.boolOrNull("disabled") != true) {
                            val rem = b.doubleOrNull("remainingFraction")?.coerceIn(0.0, 1.0)
                            if (rem != null) {
                                argonLine = MetricLine.Progress(
                                    label = "Argon Weekly",
                                    used = round((1.0 - rem) * 100.0),
                                    limit = 100.0,
                                    format = MetricFormat.Percent,
                                    resetsAtMs = ProviderHttpSupport.parseDateMs(b["resetTime"]),
                                    periodDurationMs = 7L * 24L * 3600_000L,
                                    period = "weekly",
                                )
                            }
                        }
                    }
                    continue
                }
                for (bEl in buckets) {
                    val b = bEl as? JsonObject ?: continue
                    val id = b.strOrNull("bucketId") ?: continue
                    if (specs.none { it.first == id } || pooled.containsKey(id)) continue
                    val rem = b.doubleOrNull("remainingFraction") ?: continue
                    pooled[id] = rem.coerceIn(0.0, 1.0) to ProviderHttpSupport.parseDateMs(b["resetTime"])
                }
            }
        }

        val result = mutableListOf<MetricLine>()
        specs.forEachIndexed { idx, (bucketId, label, periodMs) ->
            val entry = pooled[bucketId] ?: return@forEachIndexed
            result += MetricLine.Progress(
                label = label,
                used = round((1.0 - entry.first) * 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = entry.second,
                periodDurationMs = periodMs,
                primaryOrder = if (idx < 2) idx + 1 else null,
                period = if (periodMs >= 7L * 24L * 3600_000L) "weekly" else null,
            )
        }
        if (argonLine != null) {
            val claudeIdx = result.indexOfFirst { it.label.startsWith("Claude") }
            if (claudeIdx >= 0) result.add(claudeIdx, argonLine) else result.add(argonLine)
        }
        return result
    }

    private fun parseLegacyModels(modelsObj: JsonObject): List<MetricLine> {
        var minGeminiRem: Double? = null
        var geminiReset: Long? = null
        var minClaudeRem: Double? = null
        var claudeReset: Long? = null
        for ((key, el) in modelsObj) {
            val m = el as? JsonObject ?: continue
            if (m.boolOrNull("isInternal") == true) continue
            val label = (m.strOrNull("displayName") ?: m.strOrNull("label") ?: key).lowercase()
            if ("image" in label) continue
            val q = m.objOrNull("quotaInfo") ?: continue
            val rem = q.doubleOrNull("remainingFraction")?.coerceIn(0.0, 1.0) ?: continue
            val rst = ProviderHttpSupport.parseDateMs(q["resetTime"])
            if ("gemini" in label) {
                if (minGeminiRem == null || rem < minGeminiRem) {
                    minGeminiRem = rem
                    geminiReset = rst
                }
            } else {
                if (minClaudeRem == null || rem < minClaudeRem) {
                    minClaudeRem = rem
                    claudeReset = rst
                }
            }
        }
        return buildList {
            minGeminiRem?.let { rem ->
                add(MetricLine.Progress("Session", round((1.0 - rem) * 100.0), 100.0, MetricFormat.Percent, geminiReset, 5L * 3600_000L, primaryOrder = 1))
            }
            minClaudeRem?.let { rem ->
                add(MetricLine.Progress("Claude", round((1.0 - rem) * 100.0), 100.0, MetricFormat.Percent, claudeReset, 5L * 3600_000L))
            }
        }
    }

    private fun readPlanName(assist: JsonObject?): String? {
        val paid = assist?.objOrNull("paidTier")
        paid?.strOrNull("name")?.let { return it }
        return when (paid?.strOrNull("id")) {
            "g1-pro-tier" -> "Google AI Pro"
            "g1-ultra-tier" -> "Google AI Ultra"
            "g1-ultra-lite-tier" -> "Google AI Ultra Lite"
            else -> null
        }
    }

    private suspend fun refreshGoogleToken(secret: AccountSecret): AccountSecret {
        val clientId = oauthClientIdProvider().trim()
        val clientSecret = oauthClientSecretProvider().trim()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw ProviderProbeException(
                "Google OAuth クライアント設定が未構成のため自動更新できません。再ログインしてください。",
                isAuthError = true,
            )
        }
        val parts = secret.refreshToken.split('|')
        val cleanRefresh = parts.firstOrNull()?.trim().orEmpty()
        val embeddedProject = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            ?: parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val oauthUrl = overrides.resolve("google-oauth", AntigravityOAuthConfig.DEFAULT_OAUTH_BASE_URL) + "/token"
        val form = "client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
            "&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}" +
            "&refresh_token=${URLEncoder.encode(cleanRefresh, "UTF-8")}" +
            "&grant_type=refresh_token"
        val resp = ProviderHttpSupport.postForm(http, oauthUrl, form)
        if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("Google OAuth リフレッシュトークンが無効または失効しています", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) throw ProviderProbeException("Google トークン更新失敗 (HTTP ${resp.statusCode})")
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText) ?: throw ProviderProbeException("Google トークン更新レスポンス不正")
        val access = obj.strOrNull("access_token") ?: throw ProviderProbeException("Google access_token 欠落")
        val rotatedRefresh = obj.strOrNull("refresh_token") ?: cleanRefresh
        val exp = (obj.longOrNull("expires_in") ?: 3600L) * 1000L + System.currentTimeMillis()
        return secret.copy(
            accessToken = access,
            refreshToken = rotatedRefresh,
            expiresAtMs = exp,
            accountId = secret.accountId.ifBlank { embeddedProject.orEmpty() },
        )
    }
}
