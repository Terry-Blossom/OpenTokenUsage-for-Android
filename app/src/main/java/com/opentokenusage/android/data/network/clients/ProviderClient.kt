package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.auth.AntigravityOAuthConfig
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.storage.ClaudeResetGrantsCache
import com.opentokenusage.android.data.storage.InMemoryClaudeResetGrantsCache
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import okhttp3.OkHttpClient

data class ProbeResult(
    val plan: String? = null,
    val lines: List<MetricLine> = emptyList(),
    val updatedSecret: AccountSecret? = null,
)

/**
 * Optional base URL overrides keyed by ProviderId.id or specific helper endpoint keys,
 * enabling deterministic MockWebServer unit tests without mutating production code.
 */
data class ProviderEndpointOverrides(
    val baseUrls: Map<String, String> = emptyMap(),
) {
    fun resolve(key: String, defaultUrl: String): String =
        baseUrls[key]?.trimEnd('/') ?: defaultUrl
}

interface ProviderClient {
    suspend fun probe(
        account: ConfiguredAccount,
        secret: AccountSecret,
        nowMs: Long = System.currentTimeMillis(),
    ): ProbeResult
}

class ProviderClientRegistry(
    httpClient: OkHttpClient,
    overrides: ProviderEndpointOverrides = ProviderEndpointOverrides(),
    antigravityOAuthClientIdProvider: () -> String = AntigravityOAuthConfig::resolveClientId,
    antigravityOAuthClientSecretProvider: () -> String = AntigravityOAuthConfig::resolveClientSecret,
    claudeResetGrantsCache: ClaudeResetGrantsCache = InMemoryClaudeResetGrantsCache(),
) {
    private val clients: Map<String, ProviderClient> = mapOf(
        ProviderId.OPENROUTER.id to OpenRouterClient(httpClient, overrides),
        ProviderId.DEEPSEEK.id to DeepSeekClient(httpClient, overrides),
        ProviderId.ZAI.id to ZaiClient(httpClient, overrides),
        ProviderId.MINIMAX.id to MiniMaxClient(httpClient, overrides),
        ProviderId.SYNTHETIC.id to SyntheticClient(httpClient, overrides),
        ProviderId.OPENCODE_GO.id to OpenCodeGoClient(httpClient, overrides),
        ProviderId.AMP.id to AmpClient(httpClient, overrides),
        ProviderId.DEVIN.id to DevinClient(httpClient, overrides),
        ProviderId.CLAUDE.id to ClaudeClient(httpClient, overrides, claudeResetGrantsCache),
        ProviderId.CODEX.id to CodexClient(httpClient, overrides),
        ProviderId.COPILOT.id to CopilotClient(httpClient, overrides),
        ProviderId.CURSOR.id to CursorClient(httpClient, overrides),
        ProviderId.KIMI.id to KimiClient(httpClient, overrides),
        ProviderId.KIMI_API.id to KimiApiClient(httpClient, overrides),
        ProviderId.KILO.id to KiloClient(httpClient, overrides),
        ProviderId.CODEBUFF.id to CodebuffClient(httpClient, overrides),
        ProviderId.GROK.id to GrokClient(httpClient, overrides),
        ProviderId.MUSE.id to MuseClient(httpClient, overrides),
        ProviderId.FACTORY.id to FactoryClient(httpClient, overrides),
        ProviderId.KIRO.id to KiroClient(httpClient, overrides),
        ProviderId.CUSTOM_BALANCE.id to CustomBalanceClient(httpClient, overrides),
        ProviderId.OLLAMA.id to OllamaClient(httpClient, overrides),
        ProviderId.ANTIGRAVITY.id to AntigravityClient(
            httpClient,
            overrides,
            oauthClientIdProvider = antigravityOAuthClientIdProvider,
            oauthClientSecretProvider = antigravityOAuthClientSecretProvider,
        ),
    )

    fun clientFor(providerId: String): ProviderClient =
        clients[providerId] ?: throw ProviderProbeException("未対応のプロバイダー: $providerId")
}
