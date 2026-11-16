package com.opentokenusage.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class MiniMaxRegion(val label: String, val baseUrl: String) {
    GLOBAL("Global (minimax.io)", "https://www.minimax.io/v1/token_plan/remains"),
    CN("China (minimaxi.com)", "https://api.minimaxi.com/v1/token_plan/remains"),
}

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
