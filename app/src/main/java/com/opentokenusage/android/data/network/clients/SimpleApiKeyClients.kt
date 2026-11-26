package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.boolOrNull
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.longOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.round

private fun requireApiKey(secret: AccountSecret, name: String): String =
    secret.apiKey.ifBlank { secret.accessToken }.trim().takeIf { it.isNotEmpty() }
        ?: throw ProviderProbeException("$name の API Key が未設定です", isAuthError = true)

private fun checkHttpErrors(status: Int, providerName: String, retryAfterHeader: String? = null) {
    if (status == 401 || status == 403) {
        throw ProviderProbeException("$providerName の認証に失敗しました (HTTP $status)", isAuthError = true, statusCode = status)
    }
    if (status == 429) {
        throw ProviderProbeException(
            message = "$providerName がレート制限中 (HTTP 429)",
            isAuthError = false,
            retryAfterMs = ProviderHttpSupport.parseRetryAfterMs(retryAfterHeader),
            statusCode = 429,
        )
    }
    if (status !in 200..299) {
        throw ProviderProbeException("$providerName の取得に失敗 (HTTP $status)", statusCode = status)
    }
}

class OpenRouterClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "OpenRouter")
        val base = overrides.resolve("openrouter", "https://openrouter.ai")
        val headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")

        val keyResp = ProviderHttpSupport.get(http, "$base/api/v1/key", headers)
        checkHttpErrors(keyResp.statusCode, "OpenRouter", keyResp.firstHeader("Retry-After"))
        val keyObj = ProviderHttpSupport.tryParseObject(keyResp.bodyText)?.objOrNull("data")
            ?: throw ProviderProbeException("OpenRouter のレスポンス解析に失敗")

        val lines = mutableListOf<MetricLine>()
        val limit = keyObj.doubleOrNull("limit")
        val limitRemaining = keyObj.doubleOrNull("limit_remaining")
        val limitReset = keyObj.strOrNull("limit_reset")?.lowercase(Locale.ROOT)
        val (resetsAtMs, periodMs) = openRouterResetWindow(limitReset, nowMs)

        if (limit != null && limit > 0.0 && limitRemaining != null) {
            lines += MetricLine.Progress(
                label = "API Key 上限",
                used = max(0.0, limit - limitRemaining),
                limit = limit,
                format = MetricFormat.Dollars,
                resetsAtMs = resetsAtMs,
                periodDurationMs = periodMs,
                primaryOrder = 1,
                period = when (limitReset) {
                    "weekly" -> "weekly"
                    "monthly" -> "monthly"
                    else -> null
                },
            )
        }

        val creditsResp = runCatching { ProviderHttpSupport.get(http, "$base/api/v1/credits", headers) }.getOrNull()
        val creditsObj = if (creditsResp != null && creditsResp.statusCode in 200..299) {
            ProviderHttpSupport.tryParseObject(creditsResp.bodyText)?.objOrNull("data")
        } else {
            null
        }

        val totalCredits = creditsObj?.doubleOrNull("total_credits")
        val totalUsage = creditsObj?.doubleOrNull("total_usage")
        if (totalCredits != null && totalCredits > 0.0 && totalUsage != null) {
            lines += MetricLine.Progress(
                label = "クレジット残高",
                used = max(0.0, totalUsage),
                limit = totalCredits,
                format = MetricFormat.Dollars,
                primaryOrder = if (lines.isEmpty()) 1 else 2,
            )
        }

        keyObj.doubleOrNull("usage_daily")?.let { daily ->
            lines += MetricLine.Text(
                label = "本日の利用額",
                value = String.format(Locale.US, "$%.3f", daily),
                scope = if (lines.isEmpty()) "overview" else "detail",
            )
        }
        keyObj.doubleOrNull("usage_monthly")?.let { monthly ->
            lines += MetricLine.Text(
                label = "今月の利用額",
                value = String.format(Locale.US, "$%.2f", monthly),
                scope = "detail",
            )
        }

        val tier = if (keyObj.boolOrNull("is_free_tier") == true) "Free" else "Paid"
        if (lines.isEmpty()) {
            lines += MetricLine.Text(label = "ステータス", value = "利用可能 (上限なし)")
        }
        return ProbeResult(plan = tier, lines = lines)
    }

    private fun openRouterResetWindow(limitReset: String?, nowMs: Long): Pair<Long?, Long?> {
        val utcNow = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC)
        return when (limitReset) {
            "daily" -> {
                val next = utcNow.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                next to 24L * 3600_000L
            }
            "weekly" -> {
                val nextMon = utcNow.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                nextMon to 7L * 24L * 3600_000L
            }
            "monthly" -> {
                val nextFirst = utcNow.toLocalDate().withDayOfMonth(1).plusMonths(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                nextFirst to 30L * 24L * 3600_000L
            }
            else -> null to null
        }
    }
}

class DeepSeekClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "DeepSeek")
        val base = overrides.resolve("deepseek", "https://api.deepseek.com")
        val resp = ProviderHttpSupport.get(
            http,
            "$base/user/balance",
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
        )
        checkHttpErrors(resp.statusCode, "DeepSeek", resp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("DeepSeek のレスポンス解析に失敗")

        val isAvailable = root.boolOrNull("is_available") ?: false
        val infos = root.arrOrNull("balance_infos") ?: JsonArray(emptyList())
        val firstInfo = infos.firstOrNull() as? JsonObject
        val currency = firstInfo?.strOrNull("currency") ?: "USD"
        val total = firstInfo?.doubleOrNull("total_balance") ?: 0.0
        val granted = firstInfo?.doubleOrNull("granted_balance")
        val toppedUp = firstInfo?.doubleOrNull("topped_up_balance")
        val sym = when (currency.uppercase(Locale.ROOT)) {
            "CNY" -> "¥"
            "USD" -> "$"
            else -> "$currency "
        }

        val lines = mutableListOf<MetricLine>(
            MetricLine.Text(
                label = "残高 ($currency)",
                value = String.format(Locale.US, "%s%.2f", sym, total),
                subtitle = if (isAvailable) "利用可能" else "残高不足",
                scope = "overview",
            ),
            MetricLine.Badge(
                label = "ステータス",
                text = if (isAvailable) "有効" else "停止中",
                colorHex = if (isAvailable) "#22C55E" else "#EF4444",
                scope = "overview",
            ),
        )
        if (toppedUp != null) {
            lines += MetricLine.Text("チャージ残高", String.format(Locale.US, "%s%.2f", sym, toppedUp), scope = "detail")
        }
        if (granted != null && granted > 0.0) {
            lines += MetricLine.Text("付与クレジット", String.format(Locale.US, "%s%.2f", sym, granted), scope = "detail")
        }
        return ProbeResult(plan = currency, lines = lines)
    }
}

class ZaiClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "Z.ai")
        val base = overrides.resolve("zai", "https://api.z.ai")
        val headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")

        val subResp = runCatching { ProviderHttpSupport.get(http, "$base/api/biz/subscription/list", headers) }.getOrNull()
        val planName = if (subResp != null && subResp.statusCode in 200..299) {
            val arr = ProviderHttpSupport.tryParseObject(subResp.bodyText)?.arrOrNull("data")
            (arr?.firstOrNull() as? JsonObject)?.strOrNull("productName")
        } else {
            null
        }

        val quotaResp = ProviderHttpSupport.get(http, "$base/api/monitor/usage/quota/limit", headers)
        checkHttpErrors(quotaResp.statusCode, "Z.ai", quotaResp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(quotaResp.bodyText)
            ?: throw ProviderProbeException("Z.ai のレスポンス解析に失敗")

        if (root.boolOrNull("success") == false) {
            val msg = root.strOrNull("msg") ?: "Z.ai クォータ取得エラー"
            throw ProviderProbeException(msg)
        }
        val dataObj = root.objOrNull("data") ?: root
        val limits = dataObj.arrOrNull("limits") ?: throw ProviderProbeException("Z.ai クォータ情報が見つかりません")

        val lines = mutableListOf<MetricLine>()
        for (item in limits) {
            val obj = item as? JsonObject ?: continue
            val type = obj.strOrNull("type") ?: continue
            val unit = obj.longOrNull("unit")
            val pct = obj.doubleOrNull("percentage")
            val resetMs = ProviderHttpSupport.parseDateMs(obj["nextResetTime"])
            when {
                type == "TOKENS_LIMIT" || type == "CREDIT_LIMIT" -> {
                    if (unit == 3L && pct != null) {
                        lines += MetricLine.Progress(
                            label = "Session (5h)",
                            used = pct.coerceIn(0.0, 100.0),
                            limit = 100.0,
                            format = MetricFormat.Percent,
                            resetsAtMs = resetMs,
                            periodDurationMs = 5L * 3600_000L,
                            primaryOrder = 1,
                        )
                    } else if (unit == 6L && pct != null) {
                        lines += MetricLine.Progress(
                            label = "Weekly (7d)",
                            used = pct.coerceIn(0.0, 100.0),
                            limit = 100.0,
                            format = MetricFormat.Percent,
                            resetsAtMs = resetMs,
                            periodDurationMs = 7L * 24L * 3600_000L,
                            primaryOrder = 2,
                            period = "weekly",
                        )
                    }
                }
                type == "TIME_LIMIT" -> {
                    val used = obj.doubleOrNull("currentValue") ?: continue
                    val limit = obj.doubleOrNull("usage") ?: continue
                    if (limit > 0.0) {
                        lines += MetricLine.Progress(
                            label = "Web検索",
                            used = used.coerceAtLeast(0.0),
                            limit = limit,
                            format = MetricFormat.Count("回"),
                            resetsAtMs = resetMs,
                            scope = "overview",
                        )
                    }
                }
            }
        }
        if (lines.isEmpty()) throw ProviderProbeException("有効なクォータ枠が見つかりません")
        return ProbeResult(plan = planName, lines = lines)
    }
}

class MiniMaxClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "MiniMax")
        val url = overrides.resolve("minimax", account.region.baseUrl)
        val resp = ProviderHttpSupport.get(
            http,
            url,
            mapOf(
                "Authorization" to "Bearer $key",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
        )
        checkHttpErrors(resp.statusCode, "MiniMax", resp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("MiniMax のレスポンス解析に失敗")

        val data = root.objOrNull("data") ?: root
        val baseResp = data.objOrNull("base_resp") ?: root.objOrNull("base_resp")
        val statusCode = baseResp?.longOrNull("status_code")
        if (statusCode != null && statusCode != 0L) {
            val msg = baseResp.strOrNull("status_msg") ?: "エラー ($statusCode)"
            val isAuth = statusCode == 1004L || msg.contains("login", ignoreCase = true) || msg.contains("cookie", ignoreCase = true)
            throw ProviderProbeException("MiniMax エラー: $msg", isAuthError = isAuth)
        }

        val remainsArr = data.arrOrNull("model_remains") ?: root.arrOrNull("model_remains")
        val firstModel = remainsArr?.firstOrNull() as? JsonObject ?: data
        val isCn = account.region == com.opentokenusage.android.domain.model.MiniMaxRegion.CN
        val divisor = if (isCn) 15.0 else 1.0

        val totalRaw = firstModel.doubleOrNull("current_interval_total_count")
        val remainingRaw = firstModel.doubleOrNull("current_interval_usage_count")
        val remainingPct = firstModel.doubleOrNull("current_interval_remaining_percent")
        val endTimeMs = ProviderHttpSupport.parseDateMs(firstModel["end_time"])
        val startTimeMs = ProviderHttpSupport.parseDateMs(firstModel["start_time"])
        val remainsMsRaw = firstModel.longOrNull("remains_time")
        val resetsAtMs = endTimeMs ?: remainsMsRaw?.let {
            nowMs + if (it < 100_000L) it * 1000L else it
        }
        val periodMs = if (startTimeMs != null && endTimeMs != null && endTimeMs > startTimeMs) {
            endTimeMs - startTimeMs
        } else {
            5L * 3600_000L
        }

        val lines = mutableListOf<MetricLine>()
        if (totalRaw != null && totalRaw > 0.0 && remainingRaw != null) {
            val limit = round(totalRaw / divisor)
            val remaining = (remainingRaw / divisor).coerceIn(0.0, limit)
            lines += MetricLine.Progress(
                label = "Session (5h)",
                used = max(0.0, limit - remaining),
                limit = limit,
                format = MetricFormat.Count("回"),
                resetsAtMs = resetsAtMs,
                periodDurationMs = periodMs,
                primaryOrder = 1,
            )
        } else if (remainingPct != null) {
            lines += MetricLine.Progress(
                label = "Session (5h)",
                used = (100.0 - remainingPct).coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = resetsAtMs,
                periodDurationMs = periodMs,
                primaryOrder = 1,
            )
        }

        if (lines.isEmpty()) throw ProviderProbeException("MiniMax の利用枠データが空です")
        return ProbeResult(plan = if (isCn) "CN" else "Global", lines = lines)
    }
}

class SyntheticClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "Synthetic")
        val base = overrides.resolve("synthetic", "https://api.synthetic.new")
        val resp = ProviderHttpSupport.get(
            http,
            "$base/v2/quotas",
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
        )
        checkHttpErrors(resp.statusCode, "Synthetic", resp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Synthetic のレスポンス解析に失敗")

        val lines = mutableListOf<MetricLine>()
        root.objOrNull("rollingFiveHourLimit")?.let { rolling ->
            val maxVal = rolling.doubleOrNull("max")
            val remVal = rolling.doubleOrNull("remaining")
            if (maxVal != null && maxVal > 0.0 && remVal != null) {
                lines += MetricLine.Progress(
                    label = "5時間枠",
                    used = max(0.0, maxVal - remVal),
                    limit = maxVal,
                    format = MetricFormat.Count("req"),
                    resetsAtMs = ProviderHttpSupport.parseDateMs(rolling["nextTickAt"] ?: rolling["renewsAt"]),
                    periodDurationMs = 5L * 3600_000L,
                    primaryOrder = 1,
                )
            }
        }
        root.objOrNull("weeklyTokenLimit")?.let { weekly ->
            val remPct = weekly.doubleOrNull("percentRemaining")
            if (remPct != null) {
                lines += MetricLine.Progress(
                    label = "週間トークン",
                    used = (100.0 - remPct).coerceIn(0.0, 100.0),
                    limit = 100.0,
                    format = MetricFormat.Percent,
                    resetsAtMs = ProviderHttpSupport.parseDateMs(weekly["nextRegenAt"] ?: weekly["renewsAt"]),
                    periodDurationMs = 7L * 24L * 3600_000L,
                    primaryOrder = 2,
                    period = "weekly",
                )
            }
        }
        root.objOrNull("subscription")?.let { sub ->
            val requests = sub.doubleOrNull("requests")
            val limit = sub.doubleOrNull("limit")
            if (requests != null && limit != null && limit > 0.0) {
                lines += MetricLine.Progress(
                    label = "サブスク枠",
                    used = requests,
                    limit = limit,
                    format = MetricFormat.Count("req"),
                    resetsAtMs = ProviderHttpSupport.parseDateMs(sub["renewsAt"]),
                    scope = if (lines.isEmpty()) "overview" else "detail",
                )
            }
        }
        root.objOrNull("search")?.objOrNull("hourly")?.let { search ->
            val requests = search.doubleOrNull("requests")
            val limit = search.doubleOrNull("limit")
            if (requests != null && limit != null && limit > 0.0) {
                lines += MetricLine.Progress(
                    label = "検索 (1h)",
                    used = requests,
                    limit = limit,
                    format = MetricFormat.Count("回"),
                    resetsAtMs = ProviderHttpSupport.parseDateMs(search["renewsAt"]),
                    periodDurationMs = 3600_000L,
                    scope = "detail",
                )
            }
        }

        if (lines.isEmpty()) throw ProviderProbeException("Synthetic クォータ情報が取得できませんでした")
        return ProbeResult(lines = lines)
    }
}

class OpenCodeGoClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "OpenCode Go")
        val base = overrides.resolve("opencode-go", "https://opencode.ai")
        val resp = ProviderHttpSupport.get(
            http,
            "$base/zen/go/v1/usage",
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
        )
        checkHttpErrors(resp.statusCode, "OpenCode Go", resp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("OpenCode Go のレスポンス解析に失敗")

        val usage = root.objOrNull("usage") ?: root
        val lines = mutableListOf<MetricLine>()

        fun addWindow(
            keyName: String,
            label: String,
            periodMs: Long,
            order: Int?,
            scope: String = "overview",
            period: String? = null,
        ) {
            val w = usage.objOrNull(keyName) ?: return
            val pct = w.doubleOrNull("percent") ?: return
            lines += MetricLine.Progress(
                label = label,
                used = pct.coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = ProviderHttpSupport.parseDateMs(w["resetsAt"]),
                periodDurationMs = periodMs,
                primaryOrder = order,
                period = period,
                scope = scope,
            )
        }

        addWindow("rolling", "Session", 5L * 3600_000L, 1)
        addWindow("weekly", "Weekly", 7L * 24L * 3600_000L, 2, period = "weekly")
        addWindow("monthly", "Monthly", 30L * 24L * 3600_000L, null, scope = "detail", period = "monthly")

        if (lines.isEmpty()) throw ProviderProbeException("OpenCode Go の利用枠が見つかりません")
        return ProbeResult(plan = "Go", lines = lines)
    }
}

class AmpClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "Amp")
        val base = overrides.resolve("amp", "https://ampcode.com")
        val resp = ProviderHttpSupport.postJson(
            http,
            "$base/api/internal",
            """{"method":"userDisplayBalanceInfo","params":{}}""",
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
        )
        checkHttpErrors(resp.statusCode, "Amp", resp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Amp のレスポンス解析に失敗")

        val displayText = root.objOrNull("result")?.strOrNull("displayText")
            ?: throw ProviderProbeException("Amp 残高表示が見つかりません")

        val lines = mutableListOf<MetricLine>()
        val remMatch = Regex("""\$([\d.]+)/\$([\d.]+)\s*remaining""", RegexOption.IGNORE_CASE).find(displayText)
        val rateMatch = Regex("""replenishes\s*\+\$([\d.]+)/hour""", RegexOption.IGNORE_CASE).find(displayText)
        val bonusMatch = Regex("""\+(\d+)%\s*bonus\s*for\s*(\d+)\s*more\s*days""", RegexOption.IGNORE_CASE).find(displayText)
        val indMatch = Regex("""Individual credits:\s*\$([\d.]+)\s*remaining""", RegexOption.IGNORE_CASE).find(displayText)

        if (remMatch != null) {
            val remaining = remMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val total = remMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            val ratePerHour = rateMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val etaMs = if (ratePerHour != null && ratePerHour > 0.0 && total > remaining) {
                nowMs + (((total - remaining) / ratePerHour) * 3600_000.0).toLong()
            } else {
                null
            }
            if (total > 0.0) {
                lines += MetricLine.Progress(
                    label = "Amp Free",
                    used = max(0.0, total - remaining),
                    limit = total,
                    format = MetricFormat.Dollars,
                    resetsAtMs = etaMs,
                    primaryOrder = 1,
                )
            }
        }
        if (indMatch != null) {
            lines += MetricLine.Text(
                label = "個別クレジット",
                value = "$${indMatch.groupValues[1]} 残り",
                scope = if (lines.isEmpty()) "overview" else "detail",
            )
        }
        if (bonusMatch != null) {
            lines += MetricLine.Badge(
                label = "ボーナス",
                text = "+${bonusMatch.groupValues[1]}% (あと${bonusMatch.groupValues[2]}日)",
                scope = "detail",
            )
        }
        if (lines.isEmpty()) {
            lines += MetricLine.Text(label = "残高情報", value = displayText.lines().firstOrNull().orEmpty())
        }
        return ProbeResult(plan = if (indMatch != null) "Paid" else "Free", lines = lines)
    }
}

class DevinClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {
    override suspend fun probe(account: ConfiguredAccount, secret: AccountSecret, nowMs: Long): ProbeResult {
        val key = requireApiKey(secret, "Devin")
        val base = overrides.resolve(
            "devin",
            account.customHost.takeIf { it.startsWith("http") }
                ?: secret.apiServerUrl.takeIf { it.startsWith("http") }
                ?: "https://server.codeium.com",
        )
        val payload = buildJsonObject {
            put(
                "metadata",
                buildJsonObject {
                    put("apiKey", key)
                    put("ideName", "devin")
                    put("ideVersion", "1.95.4")
                    put("extensionName", "devin")
                    put("extensionVersion", "1.95.4")
                    put("locale", "en")
                },
            )
        }.toString()
        val resp = ProviderHttpSupport.postJson(
            http,
            "$base/exa.seat_management_pb.SeatManagementService/GetUserStatus",
            payload,
            mapOf("Connect-Protocol-Version" to "1", "Accept" to "application/json"),
        )
        checkHttpErrors(resp.statusCode, "Devin", resp.firstHeader("Retry-After"))
        val root = ProviderHttpSupport.tryParseObject(resp.bodyText)
            ?: throw ProviderProbeException("Devin のレスポンス解析に失敗")

        val planStatus = root.objOrNull("planStatus") ?: root
        val planInfo = planStatus.objOrNull("planInfo")
        val planName = planInfo?.strOrNull("planName")

        val lines = mutableListOf<MetricLine>()
        planStatus.doubleOrNull("dailyQuotaRemainingPercent")?.let { remPct ->
            lines += MetricLine.Progress(
                label = "Daily",
                used = (100.0 - remPct).coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = ProviderHttpSupport.parseDateMs(planStatus["dailyQuotaResetAtUnix"]),
                periodDurationMs = 24L * 3600_000L,
                primaryOrder = 1,
            )
        }
        planStatus.doubleOrNull("weeklyQuotaRemainingPercent")?.let { remPct ->
            lines += MetricLine.Progress(
                label = "Weekly",
                used = (100.0 - remPct).coerceIn(0.0, 100.0),
                limit = 100.0,
                format = MetricFormat.Percent,
                resetsAtMs = ProviderHttpSupport.parseDateMs(planStatus["weeklyQuotaResetAtUnix"]),
                periodDurationMs = 7L * 24L * 3600_000L,
                primaryOrder = 2,
                period = "weekly",
            )
        }
        planStatus.doubleOrNull("availablePromptCredits")?.let { credits ->
            lines += MetricLine.Text("プロンプトクレジット", credits.toLong().toString(), scope = "detail")
        }
        if (lines.isEmpty()) throw ProviderProbeException("Devin クォータ情報が取得できませんでした")
        return ProbeResult(plan = planName, lines = lines)
    }
}
