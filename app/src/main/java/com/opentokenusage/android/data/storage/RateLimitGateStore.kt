package com.opentokenusage.android.data.storage

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RateLimitGateEntry(
    val blockedUntilMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val lastAttemptMs: Long = 0L,
)

/**
 * Persistent HTTP 429 Retry-After backoff and minimum polling gap store
 * matching OpenTokenUsage http-gates semantics.
 */
class RateLimitGateStore(
    context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val file = File(File(context.applicationContext.filesDir, "otu_store").apply { mkdirs() }, "gates.json")

    suspend fun checkGate(
        accountId: String,
        providerId: String,
        isManual: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): GateDecision = mutex.withLock {
        val entries = loadLocked()
        val entry = entries[accountId] ?: return@withLock GateDecision.Allowed

        if (entry.blockedUntilMs > nowMs) {
            val remainingMs = entry.blockedUntilMs - nowMs
            return@withLock GateDecision.Blocked(remainingMs, isHardRateLimit = true)
        }

        if (!isManual) {
            val minGapMs = minimumAutoGapMs(providerId)
            if (minGapMs > 0L && entry.lastAttemptMs > 0L) {
                val nextAllowed = entry.lastAttemptMs + minGapMs
                if (nextAllowed > nowMs) {
                    return@withLock GateDecision.Blocked(nextAllowed - nowMs, isHardRateLimit = false)
                }
            }
        }
        GateDecision.Allowed
    }

    suspend fun recordAttempt(accountId: String, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        val map = loadLocked().toMutableMap()
        val current = map[accountId] ?: RateLimitGateEntry()
        map[accountId] = current.copy(lastAttemptMs = nowMs)
        saveLocked(map)
    }

    suspend fun recordSuccess(accountId: String, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        val map = loadLocked().toMutableMap()
        val current = map[accountId] ?: RateLimitGateEntry()
        map[accountId] = current.copy(
            blockedUntilMs = 0L,
            lastSuccessMs = nowMs,
            lastAttemptMs = nowMs,
        )
        saveLocked(map)
    }

    suspend fun recordRateLimit(
        accountId: String,
        retryAfterMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val map = loadLocked().toMutableMap()
        val clampedBackoff = retryAfterMs.coerceIn(60_000L, 6 * 60 * 60_000L)
        val current = map[accountId] ?: RateLimitGateEntry()
        map[accountId] = current.copy(
            blockedUntilMs = maxOf(current.blockedUntilMs, nowMs + clampedBackoff),
            lastAttemptMs = nowMs,
        )
        saveLocked(map)
    }

    suspend fun clear(accountId: String) = mutex.withLock {
        val map = loadLocked().toMutableMap()
        if (map.remove(accountId) != null) saveLocked(map)
    }

    private fun loadLocked(): Map<String, RateLimitGateEntry> {
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, RateLimitGateEntry>>(text) }.getOrElse { emptyMap() }
    }

    private fun saveLocked(map: Map<String, RateLimitGateEntry>) {
        file.writeText(json.encodeToString(map), Charsets.UTF_8)
    }

    companion object {
        fun minimumAutoGapMs(providerId: String): Long = when (providerId) {
            "claude" -> 10 * 60_000L
            "muse" -> 5 * 60_000L
            else -> 0L
        }
    }
}

sealed interface GateDecision {
    data object Allowed : GateDecision
    data class Blocked(val waitMs: Long, val isHardRateLimit: Boolean) : GateDecision
}
