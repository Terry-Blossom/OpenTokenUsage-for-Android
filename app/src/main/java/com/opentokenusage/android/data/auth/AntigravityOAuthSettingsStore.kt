package com.opentokenusage.android.data.auth

import com.opentokenusage.android.data.storage.AccountSecretsStore
import com.opentokenusage.android.domain.model.AccountSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AntigravityOAuthClientSettings(
    val clientId: String = "",
    val clientSecret: String = "",
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

class AntigravityOAuthSettingsStore(
    private val secretsStore: AccountSecretsStore,
    private val defaultClientId: String = "",
    private val defaultClientSecret: String = "",
) {
    private val _settings = MutableStateFlow(defaultSettings())
    val settings: StateFlow<AntigravityOAuthClientSettings> = _settings.asStateFlow()

    fun current(): AntigravityOAuthClientSettings = _settings.value

    suspend fun load() {
        val saved = secretsStore.getSecret(STORAGE_ACCOUNT_ID)
        _settings.value = AntigravityOAuthClientSettings(
            clientId = saved?.apiKey?.ifBlank { defaultClientId } ?: defaultClientId,
            clientSecret = saved?.refreshToken?.ifBlank { defaultClientSecret } ?: defaultClientSecret,
        )
    }

    suspend fun save(clientId: String, clientSecret: String) {
        val settings = AntigravityOAuthClientSettings(clientId.trim(), clientSecret.trim())
        require(settings.isConfigured) { "OAuth Client ID と Client Secret を入力してください。" }
        secretsStore.saveSecret(
            STORAGE_ACCOUNT_ID,
            AccountSecret(apiKey = settings.clientId, refreshToken = settings.clientSecret),
        )
        _settings.value = settings
    }

    private fun defaultSettings() = AntigravityOAuthClientSettings(
        clientId = defaultClientId.trim(),
        clientSecret = defaultClientSecret.trim(),
    )

    private companion object {
        const val STORAGE_ACCOUNT_ID = "__antigravity_oauth_client__"
    }
}
