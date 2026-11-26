package com.opentokenusage.android.data.network.clients

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.arrOrNull
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.strOrNull
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricLine
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * Ollama LAN HTTP API client reading /api/version, /api/tags, and /api/ps.
 * Requires no credentials; connects to a user-specified LAN URL (e.g. http://192.168.1.100:11434).
 */
class OllamaClient(
    private val http: OkHttpClient,
    private val overrides: ProviderEndpointOverrides,
) : ProviderClient {

    override suspend fun probe(
        account: ConfiguredAccount,
        secret: AccountSecret,
        nowMs: Long,
    ): ProbeResult {
        val rawBase = overrides.resolve("ollama", account.customHost.trim())
        val base = normalizeBaseUrl(rawBase)

        val versionResp = try {
            ProviderHttpSupport.get(http, "$base/api/version", mapOf("Accept" to "application/json"))
        } catch (e: ProviderProbeException) {
            throw ProviderProbeException(
                "Ollama サーバーに接続できません ($base)。OLLAMA_HOST=0.0.0.0:11434 で起動しているか確認してください。",
                isAuthError = false,
            )
        }
        if (versionResp.statusCode !in 200..299) {
            throw ProviderProbeException(
                "Ollama サーバーの応答エラー (HTTP ${versionResp.statusCode})",
                isAuthError = false,
                statusCode = versionResp.statusCode,
            )
        }
        val versionObj = ProviderHttpSupport.tryParseObject(versionResp.bodyText)
        val plan = versionObj?.strOrNull("version")?.let { "v$it" }

        val lines = mutableListOf<MetricLine>()

        val tagsObj = tryGetObject("$base/api/tags")
        val tagModels = tagsObj?.arrOrNull("models")
        if (tagModels != null) {
            var totalBytes = 0.0
            for (item in tagModels) {
                val size = (item as? JsonObject)?.doubleOrNull("size")
                if (size != null && size > 0.0) {
                    totalBytes += size
                }
            }
            val gb = String.format(Locale.US, "%.1f", totalBytes / 1_000_000_000.0)
            lines += MetricLine.Text(
                label = "インストール済みモデル",
                value = "${tagModels.size} · $gb GB",
                scope = "overview",
            )
        }

        val psObj = tryGetObject("$base/api/ps")
        val runningModels = psObj?.arrOrNull("models")
        if (runningModels != null) {
            val names = runningModels.mapNotNull { item ->
                (item as? JsonObject)?.strOrNull("name")
            }
            lines += MetricLine.Text(
                label = "読み込み中",
                value = if (names.isEmpty()) "—" else names.joinToString(", "),
                scope = "overview",
            )
        }

        if (lines.isEmpty()) {
            throw ProviderProbeException("Ollama のモデル情報を取得できませんでした")
        }
        return ProbeResult(plan = plan, lines = lines)
    }

    private suspend fun tryGetObject(url: String): JsonObject? {
        val resp = runCatching {
            ProviderHttpSupport.get(http, url, mapOf("Accept" to "application/json"))
        }.getOrNull() ?: return null
        if (resp.statusCode !in 200..299) return null
        return ProviderHttpSupport.tryParseObject(resp.bodyText)
    }

    companion object {
        internal fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                throw ProviderProbeException("Ollama サーバー URL を入力してください（例: http://192.168.1.100:11434）")
            }
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}
