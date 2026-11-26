package com.opentokenusage.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class MiniMaxRegion(val label: String, val baseUrl: String) {
    GLOBAL("Global (minimax.io)", "https://www.minimax.io/v1/token_plan/remains"),
    CN("China (minimaxi.com)", "https://api.minimaxi.com/v1/token_plan/remains"),
}

@Serializable
enum class CustomBalancePreset(val id: String, val label: String) {
    @SerialName("newapi")
    NEW_API("newapi", "New API / One API"),

    @SerialName("newapi-token")
    NEW_API_TOKEN("newapi-token", "New API (APIキー)"),

    @SerialName("sub2api")
    SUB2API("sub2api", "Sub2API"),

    @SerialName("custom")
    CUSTOM("custom", "独自エンドポイント"),
}

@Serializable
enum class CustomBalanceAuthHeader(val id: String, val label: String) {
    @SerialName("bearer")
    BEARER("bearer", "Authorization: Bearer"),

    @SerialName("x-api-key")
    X_API_KEY("x-api-key", "x-api-key"),
}

@Serializable
data class CustomBalanceConfig(
    val preset: CustomBalancePreset = CustomBalancePreset.NEW_API,
    val userId: String = "",
    val path: String = "",
    val authHeader: CustomBalanceAuthHeader = CustomBalanceAuthHeader.BEARER,
    val remainingPath: String = "",
    val usedPath: String = "",
    val totalPath: String = "",
    val divisor: Double = 1.0,
    val currency: String = "USD",
)

@Serializable
data class ConfiguredAccount(
    val id: String,
    val providerId: String,
    val label: String = "",
    val enabled: Boolean = true,
    val region: MiniMaxRegion = MiniMaxRegion.GLOBAL,
    val customHost: String = "",
    val sortOrder: Int = 0,
    val createdAtMs: Long = 0L,
    val customBalance: CustomBalanceConfig? = null,
) {
    fun effectiveTitle(provider: ProviderId?): String {
        val base = provider?.displayName ?: providerId
        return if (label.isBlank()) base else "$base ($label)"
    }
}

@Serializable
data class AccountSecret(
    val apiKey: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long? = null,
    val accountId: String = "",
    val sessionCookie: String = "",
    val profileArn: String = "",
    val awsRegion: String = "us-east-1",
    // Preserve Devin's saved api_server_url under the former serialized property name.
    @SerialName("syncBearerToken") val apiServerUrl: String = "",
)
