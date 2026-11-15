package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.max

class CopilotClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val token = secret.accessToken.ifBlank { secret.apiKey }.trim()
            .takeIf { it.isNotEmpty() }
            ?: throw ProviderProbeException("GitHub トークンが未設定です", isAuthError = true)

        val rawHost = account.customHost.trim().lowercase(Locale.ROOT).ifBlank { "github.com" }
        val allowed = rawHost == "github.com" || GHE_PATTERN.matches(rawHost)
        val safeHost = if (allowed) rawHost else throw ProviderProbeException("許可されないホストです: $rawHost")
        val defaultBase = if (safeHost == "github.com") "https://api.github.com" else "https://api.$safeHost"
        val base = overrides.resolve("copilot", defaultBase)

        val resp = ProviderHttpSupport.get(
            http,
            "$base/copilot_internal/user",
            mapOf(
                "Authorization" to "token $token",
                "Accept" to "application/json",
                "Editor-Version" to "vscode/1.96.2",
                "Editor-Plugin-Version" to "copilot-chat/0.26.7",
                "User-Agent" to "GitHubCopilotChat/0.26.7",
                "X-Github-Api-Version" to "2025-04-01",
            ),
        )
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ProviderProbeException("GitHub トークンが無効、または Copilot 権限が不足しています", isAuthError = true, statusCode = resp.statusCode)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("GitHub Copilot の取得に失敗 (HTTP ${resp.statusCode})", statusCode = resp.statusCode)
        }

        val data = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("GitHub Copilot のレスポンス解析に失敗")

        val resetMs = ProviderHttpSupport.parseDateMs(data["quota_reset_date"] ?: data["limited_user_reset_date"])
        val monthPeriodMs = 30L * 24L * 3600_000L
        val lines = mutableListOf<MetricLine>()

        val snapshots = data.objOrNull("quota_snapshots")
        if (snapshots != null) {
            addQuotaSnapshot(snapshots.objOrNull("premium_interactions"), "Premium requests", resetMs, monthPeriodMs, 1)?.let { lines += it }
            addQuotaSnapshot(snapshots.objOrNull("chat"), "Chat", resetMs, monthPeriodMs, 2)?.let { lines += it }
            addQuotaSnapshot(snapshots.objOrNull("completions"), "Completions", resetMs, monthPeriodMs, null)?.let { lines += it }
        }

        if (lines.isEmpty()) {
            data.objOrNull("limited_user_quotas")?.let { limited ->
                val chat = limited.doubleOrNull("chat")
                val comp = limited.doubleOrNull("completions")
                if (chat != null) lines += MetricLine.Text("Chat 残り", "${chat.toLong()}", scope = "overview")
                if (comp != null) lines += MetricLine.Text("Completions 残り", "${comp.toLong()}", scope = "overview")
            }
        }

        val plan = data.strOrNull("copilot_plan")?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercase(Locale.ROOT) }
        if (lines.isEmpty()) {
            lines += MetricLine.Badge(label = "ステータス", text = "利用可能 (無制限)")
        }
        return ProbeResult(plan = plan, lines = lines)
    }

    private fun addQuotaSnapshot(
        quota: JsonObject?,
        label: String,
        resetMs: Long?,
        periodMs: Long,
        order: Int?,
    ): MetricLine? {
        if (quota == null) return null
        if (quota.boolOrNull("unlimited") == true) {
            return MetricLine.Badge(label = label, text = "無制限", scope = "detail")
        }
        val ent = quota.doubleOrNull("entitlement")
        val rem = quota.doubleOrNull("remaining")
        val pctRem = quota.doubleOrNull("percent_remaining")
        return when {
            ent != null && ent > 0.0 && rem != null -> MetricLine.Progress(
                label = label,
                used = max(0.0, ent - rem),
                limit = ent,
                format = MetricFormat.Count(""),
                resetsAtMs = resetMs,
                periodDurationMs = periodMs,
                primaryOrder = order,
            )
            pctRem != null -> MetricLine.Progress(
                label = label,
                used = (100.0 - pctRem).coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = resetMs,
                periodDurationMs = periodMs,
                primaryOrder = order,
            )
            else -> null
        }
    }

    companion object {
        private val GHE_PATTERN = Regex("""^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+ghe\.com$""")
    }
}

class CursorClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        var currentSecret = secret
        var accessToken = secret.accessToken.trim()
        val refreshToken = secret.refreshToken.trim()
        val cookieToken = secret.sessionCookie.trim()

        if (accessToken.isEmpty() && refreshToken.isEmpty() && cookieToken.isEmpty()) {
            throw ProviderProbeException("Cursor のトークンまたはセッションCookieが未設定です", isAuthError = true)
        }

        val api2Base = overrides.resolve("cursor", "https://api2.cursor.sh")
        val webBase = overrides.resolve("cursor-web", "https://cursor.com")

        val expMs = currentSecret.expiresAtMs ?: CredentialInputParser.decodeJwtExpiryMs(accessToken)
        var updatedSecret: AccountSecret? = null
        if ((accessToken.isEmpty() || (expMs != null && nowMs >= expMs - 5 * 60_000L)) && refreshToken.isNotEmpty()) {
            currentSecret = refreshCursor(api2Base, currentSecret)
            updatedSecret = currentSecret
            accessToken = currentSecret.accessToken
        }

        val lines = mutableListOf<MetricLine>()
        var planName: String? = null

        if (accessToken.isNotEmpty()) {
            var usageResp = connectPost(api2Base, "/aiserver.v1.DashboardService/GetCurrentPeriodUsage", accessToken)
            if ((usageResp.statusCode == 401 || usageResp.statusCode == 403) && refreshToken.isNotEmpty() && updatedSecret == null) {
                currentSecret = refreshCursor(api2Base, currentSecret)
                updatedSecret = currentSecret
                accessToken = currentSecret.accessToken
                usageResp = connectPost(api2Base, "/aiserver.v1.DashboardService/GetCurrentPeriodUsage", accessToken)
            }

            if (usageResp.statusCode == 401 || usageResp.statusCode == 403) {
                throw ProviderProbeException("Cursor の認証が期限切れです", isAuthError = true, statusCode = usageResp.statusCode)
            }
            if (usageResp.statusCode in 200..299) {
                val usage = ProviderHttpSupport.tryParseObject(usageResp.bodyText)
                val planResp = runCatching {
                    connectPost(api2Base, "/aiserver.v1.DashboardService/GetPlanInfo", accessToken)
                }.getOrNull()
                val planObj = planResp?.takeIf { it.statusCode in 200..299 }
                    ?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
                    ?.objOrNull("planInfo")
                planName = planObj?.strOrNull("planName")

                val cycleStart = ProviderHttpSupport.parseDateMs(usage?.get("billingCycleStart"))
                val cycleEnd = ProviderHttpSupport.parseDateMs(usage?.get("billingCycleEnd"))
                val periodMs = if (cycleStart != null && cycleEnd != null && cycleEnd > cycleStart) {
                    cycleEnd - cycleStart
                } else {
                    30L * 24L * 3600_000L
                }

                usage?.objOrNull("planUsage")?.let { pu ->
                    val limitCents = pu.doubleOrNull("limit")
                    val remainingCents = pu.doubleOrNull("remaining")
                    val usedCents = pu.doubleOrNull("totalSpend")
                        ?: pu.doubleOrNull("includedSpend")
                        ?: if (limitCents != null && remainingCents != null) max(0.0, limitCents - remainingCents) else null
                    if (limitCents != null && limitCents > 0.0 && usedCents != null) {
                        lines += MetricLine.Progress(
                            label = "Plan usage",
                            used = usedCents / 100.0,
                            limit = limitCents / 100.0,
                            format = MetricFormat.Dollars,
                            resetsAtMs = cycleEnd,
                            periodDurationMs = periodMs,
                            primaryOrder = 1,
                        )
                    } else {
                        pu.doubleOrNull("autoPercentUsed")?.let { autoPct ->
                            lines += MetricLine.Progress(
                                label = "Auto usage",
                                used = autoPct.coerceIn(0.0, 100.0),
                                limit = 100.0,
                                format = MetricFormat.Percent,
                                resetsAtMs = cycleEnd,
                                periodDurationMs = periodMs,
                                primaryOrder = 1,
                            )
                        }
                    }
                }

                usage?.objOrNull("spendLimitUsage")?.let { su ->
                    val lim = su.doubleOrNull("individualLimit") ?: su.doubleOrNull("pooledLimit")
                    val rem = su.doubleOrNull("individualRemaining") ?: su.doubleOrNull("pooledRemaining")
                    val used = su.doubleOrNull("individualUsed") ?: su.doubleOrNull("pooledUsed") ?: su.doubleOrNull("totalSpend")
                        ?: if (lim != null && rem != null) max(0.0, lim - rem) else null
                    if (lim != null && lim > 0.0 && used != null) {
                        lines += MetricLine.Progress(
                            label = "On-demand",
                            used = used / 100.0,
                            limit = lim / 100.0,
                            format = MetricFormat.Dollars,
                            resetsAtMs = cycleEnd,
                            periodDurationMs = periodMs,
                            primaryOrder = if (lines.isEmpty()) 1 else 2,
                        )
                    }
                }

                // Grok Bot / Sand
                runCatching { connectPost(api2Base, "/aiserver.v1.DashboardService/GetSandUsageStatus", accessToken) }
                    .getOrNull()
                    ?.takeIf { it.statusCode in 200..299 }
                    ?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
                    ?.let { sand ->
                        if (sand.boolOrNull("usesPooledEnterpriseAllowance") != true && sand.boolOrNull("hasNonZeroIncludedLimit") != false) {
                            sand.doubleOrNull("usagePercent")?.takeIf { it >= 0.0 }?.let { pct ->
                                lines += MetricLine.Progress(
                                    label = "Grok Bot",
                                    used = pct.coerceIn(0.0, 100.0),
                                    limit = 100.0,
                                    format = MetricFormat.Percent,
                                    resetsAtMs = ProviderHttpSupport.parseDateMs(sand["nextResetTimestampUtc"]),
                                    periodDurationMs = 7L * 24L * 3600_000L,
                                    scope = "detail",
                                )
                            }
                        }
                    }
            }
        }

        // Fallback via WorkosCursorSessionToken Cookie / usage-summary if ConnectRPC yielded nothing
        if (lines.isEmpty()) {
            val sessionHeader = cookieToken.ifBlank {
                val sub = CredentialInputParser.decodeJwtSubject(accessToken)
                val userId = sub?.substringAfter('|', sub)
                if (!userId.isNullOrBlank()) "$userId%3A%3A$accessToken" else ""
            }
            if (sessionHeader.isNotBlank()) {
                val summaryResp = ProviderHttpSupport.get(
                    http,
                    "$webBase/api/usage-summary",
                    mapOf("Cookie" to "WorkosCursorSessionToken=$sessionHeader", "Accept" to "application/json"),
                )
                if (summaryResp.statusCode == 401 || summaryResp.statusCode == 403) {
                    throw ProviderProbeException("Cursor セッションが期限切れです", isAuthError = true)
                }
                if (summaryResp.statusCode in 200..299) {
                    val sum = ProviderHttpSupport.tryParseObject(summaryResp.bodyText)
                    if (planName == null) planName = sum?.strOrNull("membershipType")
                    val endMs = ProviderHttpSupport.parseDateMs(sum?.get("billingCycleEnd"))
                    val indPlan = sum?.objOrNull("individualUsage")?.objOrNull("plan")
                    indPlan?.doubleOrNull("totalPercentUsed")?.let { pct ->
                        lines += MetricLine.Progress(
                            label = "Total usage",
                            used = pct.coerceIn(0.0, 100.0),
                            limit = 100.0,
                            format = MetricFormat.Percent,
                            resetsAtMs = endMs,
                            periodDurationMs = 30L * 24L * 3600_000L,
                            primaryOrder = 1,
                        )
                    }
                }
            }
        }

        if (lines.isEmpty()) throw ProviderProbeException("Cursor の利用状況が取得できませんでした")
        return ProbeResult(plan = planName, lines = lines, updatedSecret = updatedSecret)
    }

    private suspend fun connectPost(base: String, path: String, token: String) =
        ProviderHttpSupport.postJson(
            http,
            "$base$path",
            "{}",
            mapOf("Authorization" to "Bearer $token", "Connect-Protocol-Version" to "1", "Accept" to "application/json"),
        )

    private suspend fun refreshCursor(base: String, secret: AccountSecret): AccountSecret {
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("client_id", CLIENT_ID)
            put("refresh_token", secret.refreshToken)
        }.toString()
        val resp = ProviderHttpSupport.postJson(http, "$base/oauth/token", body)
        if (resp.statusCode == 400 || resp.statusCode == 401) {
            throw ProviderProbeException("Cursor のセッションが期限切れです", isAuthError = true)
        }
        if (resp.statusCode !in 200..299) {
            throw ProviderProbeException("Cursor トークン更新エラー (HTTP ${resp.statusCode})")
        }
        val obj = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Cursor 更新レスポンス解析に失敗")
        val newAccess = obj.strOrNull("access_token")
            ?: throw ProviderProbeException("Cursor 更新レスポンスに access_token がありません")
        return secret.copy(
            accessToken = newAccess,
            expiresAtMs = CredentialInputParser.decodeJwtExpiryMs(newAccess),
        )
    }

    companion object {
        private const val CLIENT_ID = "KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB"
    }
}
