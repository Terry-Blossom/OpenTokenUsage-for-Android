package com.opentokenusage.android.data.storage

import android.content.Context
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.ResetCreditItem
import com.opentokenusage.android.domain.model.UsageHistoryEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedClaudeResetGrants(
    val checkedAtMs: Long = 0L,
    val eligible: Boolean = false,
    val items: List<ResetCreditItem> = emptyList(),
)

interface ClaudeResetGrantsCache {
    suspend fun load(accountId: String): CachedClaudeResetGrants?
    suspend fun save(accountId: String, entry: CachedClaudeResetGrants)
    suspend fun delete(accountId: String)
}

class InMemoryClaudeResetGrantsCache : ClaudeResetGrantsCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, CachedClaudeResetGrants>()

    override suspend fun load(accountId: String): CachedClaudeResetGrants? = map[accountId]

    override suspend fun save(accountId: String, entry: CachedClaudeResetGrants) {
        map[accountId] = entry
    }

    override suspend fun delete(accountId: String) {
        map.remove(accountId)
    }
}

@Serializable
data class FxRateCache(
    val rates: Map<String, Double> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class AlertDedupeState(
    val lastAlertKeys: Map<String, Long> = emptyMap(),
)

/**
 * Atomic JSON stores under filesDir for accounts, snapshots, settings, history,
 * FX rates, alert deduping, and Glance per-widget account bindings.
 * All collections initialize strictly empty with zero sample/mock items.
 */
class LocalAppStores(
    context: Context,
    private val json: Json,
) : ClaudeResetGrantsCache {
    private val dir = File(context.applicationContext.filesDir, "otu_store").apply { mkdirs() }
    private val ioMutex = Mutex()

    private val accountsFile = File(dir, "accounts.json")
    private val snapshotsFile = File(dir, "snapshots.json")
    private val historyFile = File(dir, "history.json")
    private val settingsFile = File(dir, "settings.json")
    private val widgetBindingsFile = File(dir, "widget_bindings.json")
    private val fxRatesFile = File(dir, "fx_rates.json")
    private val alertStateFile = File(dir, "alerts.json")
    private val claudeResetGrantsFile = File(dir, "claude_reset_grants.json")

    suspend fun loadAccounts(): List<ConfiguredAccount> = ioMutex.withLock {
        readJson(accountsFile) ?: emptyList()
    }

    suspend fun saveAccounts(accounts: List<ConfiguredAccount>) = ioMutex.withLock {
        writeJsonAtomic(accountsFile, accounts)
    }

    suspend fun loadSnapshots(): Map<String, ProviderSnapshot> = ioMutex.withLock {
        readJson(snapshotsFile) ?: emptyMap()
    }

    suspend fun saveSnapshots(snapshots: Map<String, ProviderSnapshot>) = ioMutex.withLock {
        writeJsonAtomic(snapshotsFile, snapshots)
    }

    suspend fun loadHistory(): Map<String, List<UsageHistoryEntry>> = ioMutex.withLock {
        readJson(historyFile) ?: emptyMap()
    }

    suspend fun appendHistory(accountId: String, entries: List<UsageHistoryEntry>) = ioMutex.withLock {
        if (entries.isEmpty()) return@withLock
        val current = (readJson<Map<String, List<UsageHistoryEntry>>>(historyFile) ?: emptyMap()).toMutableMap()
        val existing = current[accountId].orEmpty()
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val merged = (existing + entries)
            .filter { it.timestampMs >= cutoff }
            .takeLast(300)
        current[accountId] = merged
        writeJsonAtomic(historyFile, current)
    }

    suspend fun deleteAccountHistory(accountId: String) = ioMutex.withLock {
        val current = (readJson<Map<String, List<UsageHistoryEntry>>>(historyFile) ?: emptyMap()).toMutableMap()
        if (current.remove(accountId) != null) {
            writeJsonAtomic(historyFile, current)
        }
    }

    suspend fun loadSettings(): AppSettings = ioMutex.withLock {
        readJson(settingsFile) ?: AppSettings()
    }

    suspend fun saveSettings(settings: AppSettings) = ioMutex.withLock {
        writeJsonAtomic(settingsFile, settings)
    }

    suspend fun loadWidgetBindings(): Map<Int, String> = ioMutex.withLock {
        readJson(widgetBindingsFile) ?: emptyMap()
    }

    suspend fun setWidgetBinding(appWidgetId: Int, accountId: String) = ioMutex.withLock {
        val current = (readJson<Map<Int, String>>(widgetBindingsFile) ?: emptyMap()).toMutableMap()
        current[appWidgetId] = accountId
        writeJsonAtomic(widgetBindingsFile, current)
    }

    suspend fun removeWidgetBindings(appWidgetIds: IntArray) = ioMutex.withLock {
        val current = (readJson<Map<Int, String>>(widgetBindingsFile) ?: emptyMap()).toMutableMap()
        var changed = false
        for (id in appWidgetIds) {
            if (current.remove(id) != null) changed = true
        }
        if (changed) writeJsonAtomic(widgetBindingsFile, current)
    }

    suspend fun loadFxCache(): FxRateCache = ioMutex.withLock {
        readJson(fxRatesFile) ?: FxRateCache()
    }

    suspend fun saveFxCache(cache: FxRateCache) = ioMutex.withLock {
        writeJsonAtomic(fxRatesFile, cache)
    }

    suspend fun loadAlertState(): AlertDedupeState = ioMutex.withLock {
        readJson(alertStateFile) ?: AlertDedupeState()
    }

    suspend fun saveAlertState(state: AlertDedupeState) = ioMutex.withLock {
        writeJsonAtomic(alertStateFile, state)
    }

    override suspend fun load(accountId: String): CachedClaudeResetGrants? = ioMutex.withLock {
        readJson<Map<String, CachedClaudeResetGrants>>(claudeResetGrantsFile)?.get(accountId)
    }

    override suspend fun save(accountId: String, entry: CachedClaudeResetGrants) = ioMutex.withLock {
        val current = (readJson<Map<String, CachedClaudeResetGrants>>(claudeResetGrantsFile) ?: emptyMap()).toMutableMap()
        current[accountId] = entry
        writeJsonAtomic(claudeResetGrantsFile, current)
    }

    override suspend fun delete(accountId: String) = ioMutex.withLock {
        val current = (readJson<Map<String, CachedClaudeResetGrants>>(claudeResetGrantsFile) ?: emptyMap()).toMutableMap()
        if (current.remove(accountId) != null) {
            writeJsonAtomic(claudeResetGrantsFile, current)
        }
    }

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }

    private inline fun <reified T> writeJsonAtomic(file: File, value: T) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(value), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(value), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
