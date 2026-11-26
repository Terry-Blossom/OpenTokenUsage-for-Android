package com.opentokenusage.android.data.network

import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches each provider's public Statuspage `/api/v2/status.json` endpoint.
 * Mirrors desktop `provider_status.rs`: 5-minute cache TTL, 5-second request timeout,
 * and silent failure handling so offline/unreachable status pages never block usage refreshes.
 */
class ProviderStatusClient(
    httpClient: OkHttpClient,
    private val statusPageUrlProvider: (String) -> String? = { ProviderId.fromId(it)?.statusPageUrl },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class CacheEntry(
        val fetchedAtMs: Long,
        val status: ProviderServiceStatus,
    )

    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val providerLocks = ConcurrentHashMap<String, Mutex>()

    fun cachedStatus(providerId: String, nowMs: Long = clockMs()): ProviderServiceStatus? {
        val key = normalizeKey(providerId) ?: return null
        val entry = cache[key] ?: return null
        return if (nowMs - entry.fetchedAtMs < cacheTtlMs) entry.status else null
    }

    /**
     * Returns the provider's latest status, using the in-memory cache within [cacheTtlMs].
     * Returns null when the provider has no status page or when the network/parse check fails.
     */
    suspend fun fetchStatus(providerId: String): ProviderServiceStatus? {
        val key = normalizeKey(providerId) ?: return null
        val baseUrl = statusPageUrlProvider(key)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: return null

        val lock = providerLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val now = clockMs()
            val cached = cache[key]
            if (cached != null && now - cached.fetchedAtMs < cacheTtlMs) {
                return@withLock cached.status
            }

            val fetched = requestStatus(baseUrl, now) ?: return@withLock null
            cache[key] = CacheEntry(fetchedAtMs = now, status = fetched)
            fetched
        }
    }

    private suspend fun requestStatus(baseUrl: String, nowMs: Long): ProviderServiceStatus? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/v2/status.json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    ProviderServiceStatus.parseStatusJson(body, checkedAtMs = nowMs)
                }
            }.getOrNull()
        }

    private fun normalizeKey(providerId: String): String? =
        providerId.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 5L * 60_000L
        private const val REQUEST_TIMEOUT_SECONDS = 5L
        private const val USER_AGENT = "OpenTokenUsage"
    }
}
