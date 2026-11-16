package com.opentokenusage.android.domain.logic

import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

data class ParsedCredentialInput(
    val secret: AccountSecret,
    val detectedNote: String? = null,
)

/**
 * Extracts structured credentials whether the user pastes a raw token/key
 * or pastes an entire desktop CLI auth JSON / TOML snippet.
 */
object CredentialInputParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        providerId: ProviderId,
        primaryInput: String,
        secondaryRefreshToken: String = "",
        accountIdOrArn: String = "",
        awsRegion: String = "us-east-1",
    ): ParsedCredentialInput {
        val raw = primaryInput.trim()
        val refreshOverride = secondaryRefreshToken.trim()
        val extraId = accountIdOrArn.trim()

        if (raw.startsWith("{")) {
            val parsedObj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (parsedObj != null) {
                return parseJsonObject(providerId, parsedObj, refreshOverride, extraId, awsRegion)
            }
        }

        // Support Devin credentials.toml snippets (windsurf_api_key / api_key / apiKey)
        if (providerId == ProviderId.DEVIN && raw.contains("=")) {
            val apiKeyMatch = Regex("""(?:windsurf_api_key|api_key|apiKey)\s*=\s*["']([^"']+)["']""").find(raw)
            val urlMatch = Regex("""api_server_url\s*=\s*["']([^"']+)["']""").find(raw)
            if (apiKeyMatch != null) {
                return ParsedCredentialInput(
                    secret = AccountSecret(
                        apiKey = apiKeyMatch.groupValues[1].trim(),
                        apiServerUrl = urlMatch?.groupValues?.getOrNull(1)?.trim().orEmpty(),
                    ),
                    detectedNote = "TOML から Devin credentials を抽出しました",
                )
            }
        }

        // Cursor WorkosCursorSessionToken=... paste
        if (providerId == ProviderId.CURSOR && raw.contains("%3A%3A")) {
            val cleanCookie = raw.removePrefix("WorkosCursorSessionToken=").trim()
            val access = cleanCookie.substringAfter("%3A%3A", cleanCookie)
            return ParsedCredentialInput(
                secret = AccountSecret(
                    accessToken = access,
                    refreshToken = refreshOverride,
                    sessionCookie = cleanCookie,
                    expiresAtMs = decodeJwtExpiryMs(access),
                ),
                detectedNote = "Cursor Session Token を認識しました",
            )
        }

        val isApiKey = providerId in setOf(
            ProviderId.OPENROUTER,
            ProviderId.DEEPSEEK,
            ProviderId.ZAI,
            ProviderId.MINIMAX,
            ProviderId.SYNTHETIC,
            ProviderId.OPENCODE_GO,
            ProviderId.AMP,
            ProviderId.DEVIN,
        )

        return if (isApiKey) {
            ParsedCredentialInput(secret = AccountSecret(apiKey = raw))
        } else {
            // Treat as access token, or refresh token if prefixed/chosen as refresh-only when primary is blank
            val expMs = decodeJwtExpiryMs(raw)
            ParsedCredentialInput(
                secret = AccountSecret(
                    accessToken = raw,
                    refreshToken = refreshOverride,
                    expiresAtMs = expMs,
                    accountId = extraId,
                    profileArn = extraId,
                    awsRegion = awsRegion.ifBlank { "us-east-1" },
                ),
            )
        }
    }

    private fun parseJsonObject(
        providerId: ProviderId,
        root: JsonObject,
        refreshOverride: String,
        extraId: String,
        awsRegion: String,
    ): ParsedCredentialInput {
        return when (providerId) {
            ProviderId.CLAUDE -> {
                val oauth = root["claudeAiOauth"]?.jsonObject ?: root
                val access = oauth.str("accessToken") ?: oauth.str("access_token").orEmpty()
                val refresh = (oauth.str("refreshToken") ?: oauth.str("refresh_token")).orEmpty()
                    .ifBlank { refreshOverride }
                val exp = oauth.timestampMs("expiresAt") ?: decodeJwtExpiryMs(access)
                ParsedCredentialInput(
                    secret = AccountSecret(accessToken = access, refreshToken = refresh, expiresAtMs = exp),
                    detectedNote = "Claude 認証 JSON を読み込みました",
                )
            }
            ProviderId.CODEX -> {
                val tokens = root["tokens"]?.jsonObject ?: root
                val access = (tokens.str("access_token") ?: tokens.str("accessToken")).orEmpty()
                val refresh = (tokens.str("refresh_token") ?: tokens.str("refreshToken")).orEmpty()
                    .ifBlank { refreshOverride }
                val accId = (tokens.str("account_id") ?: tokens.str("accountId")).orEmpty()
                    .ifBlank { extraId }
                val exp = tokens.timestampMs("expires_at") ?: decodeJwtExpiryMs(access)
                ParsedCredentialInput(
                    secret = AccountSecret(
                        accessToken = access,
                        refreshToken = refresh,
                        accountId = accId,
                        expiresAtMs = exp,
                    ),
                    detectedNote = "Codex auth.json を読み込みました",
                )
            }
            ProviderId.KIRO -> {
                val access = (root.str("accessToken") ?: root.str("access_token")).orEmpty()
                val refresh = (root.str("refreshToken") ?: root.str("refresh_token")).orEmpty()
                    .ifBlank { refreshOverride }
                val profileArn = (root.str("profileArn") ?: root.str("profile_arn")).orEmpty()
                    .ifBlank { extraId }
                val region = (root.str("region") ?: awsRegion).ifBlank { "us-east-1" }
                val exp = root.timestampMs("expiresAt") ?: decodeJwtExpiryMs(access)
                ParsedCredentialInput(
                    secret = AccountSecret(
                        accessToken = access,
                        refreshToken = refresh,
                        profileArn = profileArn,
                        awsRegion = region,
                        expiresAtMs = exp,
                    ),
                    detectedNote = "Kiro トークン JSON を読み込みました",
                )
            }
            else -> {
                val apiKey = (root.str("apiKey") ?: root.str("api_key")).orEmpty()
                val access = (root.str("accessToken") ?: root.str("access_token") ?: root.str("token")).orEmpty()
                val refresh = (root.str("refreshToken") ?: root.str("refresh_token")).orEmpty()
                    .ifBlank { refreshOverride }
                val exp = root.timestampMs("expiresAt")
                    ?: root.timestampMs("expires_at")
                    ?: decodeJwtExpiryMs(access)
                ParsedCredentialInput(
                    secret = AccountSecret(
                        apiKey = apiKey,
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtMs = exp,
                        accountId = extraId,
                    ),
                    detectedNote = "JSON から認証情報を抽出しました",
                )
            }
        }
    }

    fun decodeJwtExpiryMs(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(padBase64(parts[1])), Charsets.UTF_8)
            val payload = json.parseToJsonElement(decoded).jsonObject
            val expSec = payload["exp"]?.jsonPrimitive?.doubleOrNull ?: return null
            (expSec * 1000.0).toLong()
        }.getOrNull()
    }

    fun decodeJwtSubject(token: String): String? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(padBase64(parts[1])), Charsets.UTF_8)
            val payload = json.parseToJsonElement(decoded).jsonObject
            payload["sub"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun padBase64(segment: String): String {
        val mod = segment.length % 4
        return if (mod == 0) segment else segment + "=".repeat(4 - mod)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.timestampMs(key: String): Long? {
        val el: JsonElement = this[key] ?: return null
        val prim = el as? JsonPrimitive ?: return null
        prim.doubleOrNull?.let { num ->
            if (!num.isFinite()) return null
            // Kimi stores expires_at in seconds (< 1e12), Claude in ms (>= 1e12)
            return if (num < 1_000_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
        }
        prim.longOrNull?.let { num ->
            return if (num < 1_000_000_000_000L) num * 1000L else num
        }
        val text = prim.contentOrNull ?: return null
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}
