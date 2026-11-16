package com.opentokenusage.android.data.auth

import com.opentokenusage.android.BuildConfig
import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.boolOrNull
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.data.network.longOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.domain.model.AccountSecret
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object AntigravityOAuthConfig {
    const val REDIRECT_HOST = "127.0.0.1"
    const val REDIRECT_PORT = 51121
    const val REDIRECT_PATH = "/oauth-callback"
    const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT$REDIRECT_PATH"
    const val APP_RETURN_URI = "opentokenusage://oauth/antigravity/done"

    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val DEFAULT_OAUTH_BASE_URL = "https://oauth2.googleapis.com"
    const val DEFAULT_USERINFO_BASE_URL = "https://www.googleapis.com"
    const val DEFAULT_DAILY_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
    const val DEFAULT_PROD_ENDPOINT = "https://cloudcode-pa.googleapis.com"

    const val GEMINI_CLI_USER_AGENT = "google-api-nodejs-client/9.15.1"
    const val ANTIGRAVITY_USER_AGENT = "antigravity/2.31.1 (aidev_client; os_type=android; arch=arm64)"
    const val ANTIGRAVITY_ONBOARD_USER_AGENT = "antigravity/hub/2.24.0 (aidev_client; os_type=android; arch=arm64; cl=1000335682) google-api-nodejs-client/10.3.0"
    const val ANTIGRAVITY_GOOG_API_CLIENT = "gl-node/22.21.1"
    const val ANTIGRAVITY_IDE_VERSION = "2.24.0"

    val SCOPES: List<String> = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    )

    fun resolveClientId(): String =
        BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_ID.trim()

    fun resolveClientSecret(): String =
        BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_SECRET.trim()

    fun isConfigured(): Boolean =
        resolveClientId().isNotEmpty() && resolveClientSecret().isNotEmpty()
}

data class PkcePair(
    val verifier: String,
    val challenge: String,
)

data class AntigravityAuthorizationRequest(
    val url: String,
    val state: String,
    val codeVerifier: String,
)

data class AntigravityCallbackParams(
    val code: String? = null,
    val state: String? = null,
    val error: String? = null,
    val errorDescription: String? = null,
)

data class AntigravityAuthResult(
    val secret: AccountSecret,
    val email: String? = null,
    val projectId: String? = null,
)

object AntigravityPkce {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generatePkcePair(): PkcePair {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        val verifier = urlEncoder.encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = urlEncoder.encodeToString(digest)
        return PkcePair(verifier = verifier, challenge = challenge)
    }

    fun generateState(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun buildAuthorizationRequest(
        clientId: String = AntigravityOAuthConfig.resolveClientId(),
        redirectUri: String = AntigravityOAuthConfig.REDIRECT_URI,
    ): AntigravityAuthorizationRequest {
        val pkce = generatePkcePair()
        val state = generateState()
        val params = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to AntigravityOAuthConfig.SCOPES.joinToString(" "),
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "prompt" to "select_account consent",
        )
        val query = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return AntigravityAuthorizationRequest(
            url = "${AntigravityOAuthConfig.AUTH_ENDPOINT}?$query",
            state = state,
            codeVerifier = pkce.verifier,
        )
    }

    /**
     * Parses a callback URL (e.g. `http://localhost:51121/oauth-callback?code=...&state=...`),
     * raw query string (`code=...&state=...`), or a bare authorization code (`4/0A...`).
     */
    fun parseCallbackInput(rawInput: String): AntigravityCallbackParams {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return AntigravityCallbackParams()

        val query = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                runCatching { URI(trimmed).rawQuery }.getOrNull()
                    ?: trimmed.substringAfter('?', "")
            }
            trimmed.contains("code=") || trimmed.contains("error=") -> {
                trimmed.substringAfter('?', trimmed)
            }
            else -> {
                return AntigravityCallbackParams(code = trimmed)
            }
        }

        val map = parseQueryString(query)
        return AntigravityCallbackParams(
            code = map["code"],
            state = map["state"],
            error = map["error"],
            errorDescription = map["error_description"],
        )
    }

    fun parseQueryString(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
            val decodedValue = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            if (decodedKey.isNotEmpty()) {
                out[decodedKey] = decodedValue
            }
        }
        return out
    }
}

class AntigravityOAuthClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides = ProviderEndpointOverrides(),
    private val clientIdProvider: () -> String = AntigravityOAuthConfig::resolveClientId,
    private val clientSecretProvider: () -> String = AntigravityOAuthConfig::resolveClientSecret,
) {
    fun isConfigured(): Boolean =
        clientIdProvider().isNotBlank() && clientSecretProvider().isNotBlank()

    fun buildAuthorizationRequest(
        redirectUri: String = AntigravityOAuthConfig.REDIRECT_URI,
    ): AntigravityAuthorizationRequest {
        val clientId = clientIdProvider().trim()
        if (clientId.isEmpty() || clientSecretProvider().isBlank()) {
            throw ProviderProbeException(
                "Antigravity の Google OAuth クライアント設定が未構成です。ビルド時に antigravityOAuthClientId / antigravityOAuthClientSecret を設定してください。",
                isAuthError = true,
            )
        }
        return AntigravityPkce.buildAuthorizationRequest(clientId = clientId, redirectUri = redirectUri)
    }

    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        redirectUri: String = AntigravityOAuthConfig.REDIRECT_URI,
        nowMs: Long = System.currentTimeMillis(),
    ): AntigravityAuthResult {
        val clientId = clientIdProvider().trim()
        val clientSecret = clientSecretProvider().trim()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw ProviderProbeException("Antigravity の OAuth クライアント設定が未構成です", isAuthError = true)
        }

        val oauthBase = overrides.resolve("google-oauth", AntigravityOAuthConfig.DEFAULT_OAUTH_BASE_URL)
        val form = listOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "code" to code,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        ).joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val tokenResp = ProviderHttpSupport.postForm(
            http,
            "$oauthBase/token",
            form,
            headers = mapOf(
                "Accept" to "*/*",
                "User-Agent" to AntigravityOAuthConfig.GEMINI_CLI_USER_AGENT,
            ),
        )
        if (tokenResp.statusCode !in 200..299) {
            val errObj = ProviderHttpSupport.tryParseObject(tokenResp.bodyText)
            val errMsg = errObj?.strOrNull("error_description")
                ?: errObj?.strOrNull("error")
                ?: "HTTP ${tokenResp.statusCode}"
            throw ProviderProbeException("Google トークン交換に失敗しました ($errMsg)", isAuthError = true)
        }

        val tokenJson = ProviderHttpSupport.tryParseObject(tokenResp.bodyText)
            ?: throw ProviderProbeException("Google トークンレスポンスが不正です")
        val accessToken = tokenJson.strOrNull("access_token")
            ?: throw ProviderProbeException("Google access_token が取得できませんでした")
        val refreshToken = tokenJson.strOrNull("refresh_token")
            ?: throw ProviderProbeException("Google refresh_token が取得できませんでした。権限の同意画面をやり直してください。", isAuthError = true)
        val expiresIn = tokenJson.longOrNull("expires_in")?.takeIf { it > 0L } ?: 3600L
        val expiresAtMs = nowMs + expiresIn * 1000L

        val email = fetchUserEmail(accessToken)
        val projectId = resolveProjectId(accessToken)

        return AntigravityAuthResult(
            secret = AccountSecret(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = expiresAtMs,
                accountId = projectId.orEmpty(),
            ),
            email = email,
            projectId = projectId,
        )
    }

    private suspend fun fetchUserEmail(accessToken: String): String? {
        val userInfoBase = overrides.resolve("google-userinfo", AntigravityOAuthConfig.DEFAULT_USERINFO_BASE_URL)
        val resp = runCatching {
            ProviderHttpSupport.get(
                http,
                "$userInfoBase/oauth2/v1/userinfo?alt=json",
                mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "User-Agent" to AntigravityOAuthConfig.GEMINI_CLI_USER_AGENT,
                ),
            )
        }.getOrNull() ?: return null
        if (resp.statusCode !in 200..299) return null
        return ProviderHttpSupport.tryParseObject(resp.bodyText)?.strOrNull("email")
    }

    private suspend fun resolveProjectId(accessToken: String): String? {
        val dailyBase = overrides.resolve("antigravity-daily", AntigravityOAuthConfig.DEFAULT_DAILY_ENDPOINT)
        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "Content-Type" to "application/json",
            "User-Agent" to AntigravityOAuthConfig.ANTIGRAVITY_USER_AGENT,
        )
        val loadResp = runCatching {
            ProviderHttpSupport.postJson(
                http,
                "$dailyBase/v1internal:loadCodeAssist",
                """{"metadata":{"ideType":"ANTIGRAVITY"}}""",
                headers,
            )
        }.getOrNull()

        val loadJson = loadResp?.takeIf { it.statusCode in 200..299 }
            ?.let { ProviderHttpSupport.tryParseObject(it.bodyText) }
        extractProjectId(loadJson)?.let { return it }

        // Provision a managed project for accounts not yet onboarded to Antigravity.
        val tierId = pickDefaultTierId(loadJson)
        val onboardBody = """{"tier_id":"$tierId","metadata":{"ide_type":"ANTIGRAVITY","ide_version":"${AntigravityOAuthConfig.ANTIGRAVITY_IDE_VERSION}","ide_name":"antigravity"}}"""
        val onboardHeaders = mapOf(
            "Accept" to "*/*",
            "Authorization" to "Bearer $accessToken",
            "Content-Type" to "application/json",
            "User-Agent" to AntigravityOAuthConfig.ANTIGRAVITY_ONBOARD_USER_AGENT,
            "X-Goog-Api-Client" to AntigravityOAuthConfig.ANTIGRAVITY_GOOG_API_CLIENT,
        )

        repeat(3) { attempt ->
            val onboardResp = runCatching {
                ProviderHttpSupport.postJson(http, "$dailyBase/v1internal:onboardUser", onboardBody, onboardHeaders)
            }.getOrNull() ?: return null
            if (onboardResp.statusCode !in 200..299) return null
            val onboardJson = ProviderHttpSupport.tryParseObject(onboardResp.bodyText) ?: return null
            if (onboardJson.boolOrNull("done") == true) {
                return extractProjectId(onboardJson.objOrNull("response"))
            }
            if (attempt < 2) delay(1500L)
        }
        return null
    }

    private fun pickDefaultTierId(loadJson: JsonObject?): String {
        val tiers = loadJson?.arrOrNull("allowedTiers")
        if (tiers != null) {
            for (el in tiers) {
                val obj = el as? JsonObject ?: continue
                if (obj.boolOrNull("isDefault") == true) {
                    obj.strOrNull("id")?.let { return it }
                }
            }
            val firstId = (tiers.firstOrNull() as? JsonObject)?.strOrNull("id")
            if (firstId != null) return firstId
        }
        return loadJson?.objOrNull("currentTier")?.strOrNull("id") ?: "free-tier"
    }

    companion object {
        fun extractProjectId(obj: JsonObject?): String? {
            if (obj == null) return null
            return obj.strOrNull("cloudaicompanionProject")
                ?: obj.objOrNull("cloudaicompanionProject")?.strOrNull("id")
        }
    }
}
