package com.opentokenusage.android.data.repository

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.opentokenusage.android.data.auth.AntigravityOAuthSettingsStore
import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.clients.ProviderClientRegistry
import com.opentokenusage.android.data.network.doubleOrNull
import com.opentokenusage.android.data.network.objOrNull
import com.opentokenusage.android.data.storage.AccountSecretsStore
import com.opentokenusage.android.data.storage.AlertDedupeState
import com.opentokenusage.android.data.storage.FxRateCache
import com.opentokenusage.android.data.storage.GateDecision
import com.opentokenusage.android.data.storage.LocalAppStores
import com.opentokenusage.android.data.storage.RateLimitGateStore
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.MiniMaxRegion
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageHistoryEntry
import com.opentokenusage.android.tile.UsageQuickSettingsTileService
import com.opentokenusage.android.util.NotificationHelper
import com.opentokenusage.android.widget.UsageMiniWidget
import com.opentokenusage.android.widget.UsageOverviewWidget
import com.opentokenusage.android.widget.UsageSingleWidget
import com.opentokenusage.android.widget.UsageStatusStripWidget
import com.opentokenusage.android.worker.UsageRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates accounts, encrypted credentials, cached snapshots, per-account single-flight
 * probes, 429 gates, alerts, FX rates, and Jetpack Glance widget updates.
 * Strictly initializes empty with zero pre-configured accounts or placeholder metrics.
 */
class UsageRepository(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val stores: LocalAppStores,
    private val secretsStore: AccountSecretsStore,
    private val antigravityOAuthSettings: AntigravityOAuthSettingsStore,
    private val gateStore: RateLimitGateStore,
    private val clientRegistry: ProviderClientRegistry,
    private val notificationHelper: NotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val perAccountLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshAllLock = Mutex()
    private val initMutex = Mutex()
    @Volatile private var initialized = false

    private val _accounts = MutableStateFlow<List<ConfiguredAccount>>(emptyList())
    val accounts: StateFlow<List<ConfiguredAccount>> = _accounts.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<String, ProviderSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ProviderSnapshot>> = _snapshots.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _history = MutableStateFlow<Map<String, List<UsageHistoryEntry>>>(emptyMap())
    val history: StateFlow<Map<String, List<UsageHistoryEntry>>> = _history.asStateFlow()

    private val _fxRates = MutableStateFlow<Map<String, Double>>(emptyMap())
    val fxRates: StateFlow<Map<String, Double>> = _fxRates.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastSyncMs = MutableStateFlow<Long?>(null)
    val lastSyncMs: StateFlow<Long?> = _lastSyncMs.asStateFlow()

    suspend fun initialize() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            antigravityOAuthSettings.load()
            val savedAccounts = stores.loadAccounts()
            val removedDesktopAccounts = savedAccounts.filter { it.providerId in LEGACY_DESKTOP_PROVIDER_IDS }
            _accounts.value = savedAccounts.filterNot { it.providerId in LEGACY_DESKTOP_PROVIDER_IDS }
            val savedSnapshots = stores.loadSnapshots()
            _snapshots.value = savedSnapshots.filter { (id, snapshot) ->
                !id.startsWith("sync_") && snapshot.providerId !in LEGACY_DESKTOP_PROVIDER_IDS
            }
            val removedSnapshotIds = savedSnapshots.keys - _snapshots.value.keys
            _settings.value = stores.loadSettings()
            _history.value = stores.loadHistory()
            _fxRates.value = stores.loadFxCache().rates
            _lastSyncMs.value = _snapshots.value.values.maxOfOrNull { it.fetchedAtMs }
            if (removedDesktopAccounts.isNotEmpty() || removedSnapshotIds.isNotEmpty()) {
                stores.saveAccounts(_accounts.value)
                stores.saveSnapshots(_snapshots.value)
                for (account in removedDesktopAccounts) {
                    secretsStore.deleteSecret(account.id)
                    gateStore.clear(account.id)
                    stores.deleteAccountHistory(account.id)
                }
                val removedAccountIds = removedDesktopAccounts.map { it.id }.toSet() + removedSnapshotIds
                val staleWidgetIds = stores.loadWidgetBindings()
                    .filterValues { it in removedAccountIds || it.startsWith("sync_") }
                    .keys
                    .toIntArray()
                if (staleWidgetIds.isNotEmpty()) stores.removeWidgetBindings(staleWidgetIds)
                UsageRefreshScheduler.syncSchedule(context, _settings.value, hasAccounts = _accounts.value.isNotEmpty())
            }
            initialized = true
        }
        runCatching {
            notificationHelper.updatePersistentNotification(orderedDisplaySnapshots(), _settings.value)
        }
        UsageQuickSettingsTileService.requestListeningState(context)
    }

    /** Tests connection cleanly without persisting yet; throws ProviderProbeException on failure. */
    suspend fun testConnection(
        providerId: ProviderId,
        label: String,
        region: MiniMaxRegion,
        customHost: String,
        secret: AccountSecret,
    ): ProviderSnapshot {
        val tempAccount = ConfiguredAccount(
            id = "test_${UUID.randomUUID()}",
            providerId = providerId.id,
            label = label.trim(),
            enabled = true,
            region = region,
            customHost = customHost.trim(),
        )
        val now = System.currentTimeMillis()
        val result = clientRegistry.clientFor(providerId.id).probe(tempAccount, secret, now)
        return ProviderSnapshot(
            accountId = tempAccount.id,
            providerId = providerId.id,
            displayName = providerId.displayName,
            accountLabel = label.trim(),
            plan = result.plan,
            lines = result.lines,
            fetchedAtMs = now,
        )
    }

    /** Adds or updates a user-configured account and immediately probes its real usage data. */
    suspend fun addAccountAndProbe(
        providerId: ProviderId,
        label: String,
        region: MiniMaxRegion,
        customHost: String,
        secret: AccountSecret,
        verifyFirst: Boolean = true,
    ): Result<ConfiguredAccount> {
        initialize()
        val newAccount = ConfiguredAccount(
            id = UUID.randomUUID().toString(),
            providerId = providerId.id,
            label = label.trim(),
            enabled = true,
            region = region,
            customHost = customHost.trim(),
            sortOrder = (_accounts.value.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAtMs = System.currentTimeMillis(),
        )

        val now = System.currentTimeMillis()
        val probeResult = if (verifyFirst) {
            try {
                clientRegistry.clientFor(providerId.id).probe(newAccount, secret, now)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        } else {
            null
        }

        val secretToStore = probeResult?.updatedSecret ?: secret
        secretsStore.saveSecret(newAccount.id, secretToStore)

        val updatedAccounts = (_accounts.value + newAccount).sortedBy { it.sortOrder }
        _accounts.value = updatedAccounts
        stores.saveAccounts(updatedAccounts)

        if (probeResult != null) {
            applyProbeResult(newAccount, probeResult, now)
        } else {
            refreshAccount(newAccount.id, isManual = true)
        }

        UsageRefreshScheduler.syncSchedule(context, _settings.value, hasAccounts = true)
        updateWidgets()
        return Result.success(newAccount)
    }

    suspend fun updateAccountCredentials(
        accountId: String,
        label: String,
        region: MiniMaxRegion,
        customHost: String,
        newSecret: AccountSecret?,
    ) {
        initialize()
        val list = _accounts.value.map {
            if (it.id == accountId) {
                it.copy(label = label.trim(), region = region, customHost = customHost.trim())
            } else {
                it
            }
        }
        _accounts.value = list
        stores.saveAccounts(list)
        if (newSecret != null) {
            secretsStore.saveSecret(accountId, newSecret)
        }
        gateStore.clear(accountId)
        refreshAccount(accountId, isManual = true)
        updateWidgets()
    }

    suspend fun setAccountEnabled(accountId: String, enabled: Boolean) {
        initialize()
        val list = _accounts.value.map { if (it.id == accountId) it.copy(enabled = enabled) else it }
        _accounts.value = list
        stores.saveAccounts(list)
        updateWidgets()
    }

    suspend fun moveAccount(accountId: String, direction: Int) {
        initialize()
        val list = _accounts.value.sortedBy { it.sortOrder }.toMutableList()
        val idx = list.indexOfFirst { it.id == accountId }
        val target = idx + direction
        if (idx < 0 || target !in list.indices) return
        val item = list.removeAt(idx)
        list.add(target, item)
        val reindexed = list.mapIndexed { i, acc -> acc.copy(sortOrder = i) }
        _accounts.value = reindexed
        stores.saveAccounts(reindexed)
        updateWidgets()
    }

    suspend fun deleteAccount(accountId: String) {
        initialize()
        val updated = _accounts.value.filterNot { it.id == accountId }
        _accounts.value = updated
        stores.saveAccounts(updated)

        secretsStore.deleteSecret(accountId)
        gateStore.clear(accountId)
        stores.deleteAccountHistory(accountId)
        stores.delete(accountId)

        val currentSnaps = _snapshots.value.toMutableMap()
        currentSnaps.remove(accountId)
        _snapshots.value = currentSnaps
        stores.saveSnapshots(currentSnaps)

        if (_settings.value.accountAlertThresholds.containsKey(accountId)) {
            val cleanedSettings = _settings.value.withAccountAlertThresholds(accountId, null)
            _settings.value = cleanedSettings
            stores.saveSettings(cleanedSettings)
        }

        UsageRefreshScheduler.syncSchedule(context, _settings.value, hasAccounts = updated.isNotEmpty())
        updateWidgets()
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        initialize()
        val updated = transform(_settings.value)
        _settings.value = updated
        stores.saveSettings(updated)
        UsageRefreshScheduler.syncSchedule(context, updated, hasAccounts = _accounts.value.isNotEmpty())
        updateWidgets()
    }

    suspend fun getSecret(accountId: String): AccountSecret? = secretsStore.getSecret(accountId)

    suspend fun getWidgetBinding(appWidgetId: Int): String? = stores.loadWidgetBindings()[appWidgetId]

    suspend fun bindWidgetAccount(appWidgetId: Int, accountId: String) {
        stores.setWidgetBinding(appWidgetId, accountId)
        updateWidgets()
    }

    suspend fun removeWidgetBindings(appWidgetIds: IntArray) {
        stores.removeWidgetBindings(appWidgetIds)
    }

    suspend fun refreshAll(isManual: Boolean): Boolean {
        initialize()
        val enabledAccounts = _accounts.value.filter { it.enabled }
        if (enabledAccounts.isEmpty()) {
            updateWidgets()
            return true
        }
        return refreshAllLock.withLock {
            _isRefreshing.value = true
            try {
                maybeRefreshFxRates()
                val results = coroutineScope {
                    enabledAccounts.map { account ->
                        async { refreshAccount(account.id, isManual = isManual) }
                    }.awaitAll()
                }
                _lastSyncMs.value = System.currentTimeMillis()
                updateWidgets()
                results.all { it }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    suspend fun refreshAccount(accountId: String, isManual: Boolean): Boolean {
        initialize()
        val account = _accounts.value.firstOrNull { it.id == accountId } ?: return true
        if (!account.enabled) return true

        val lock = perAccountLocks.getOrPut(accountId) { Mutex() }
        return lock.withLock {
            val now = System.currentTimeMillis()
            val gate = gateStore.checkGate(accountId, account.providerId, isManual, now)
            if (gate is GateDecision.Blocked) {
                if (gate.isHardRateLimit && isManual) {
                    markErrorStale(
                        account,
                        "レート制限中（あと${UsageDisplayFormatter.formatDurationJa(gate.waitMs)}待機）",
                        now,
                    )
                }
                return@withLock true
            }

            val secret = secretsStore.getSecret(accountId) ?: AccountSecret()
            gateStore.recordAttempt(accountId, now)
            try {
                val res = clientRegistry.clientFor(account.providerId).probe(account, secret, now)
                res.updatedSecret?.let { secretsStore.saveSecret(accountId, it) }
                gateStore.recordSuccess(accountId, now)
                applyProbeResult(account, res, now)
                updateWidgets()
                true
            } catch (e: ProviderProbeException) {
                if (e.statusCode == 429 && e.retryAfterMs != null) {
                    gateStore.recordRateLimit(accountId, e.retryAfterMs, now)
                }
                markErrorStale(account, e.message, now)
                updateWidgets()
                false
            } catch (e: Exception) {
                markErrorStale(account, e.message ?: "取得エラー", now)
                updateWidgets()
                false
            }
        }
    }

    private suspend fun applyProbeResult(
        account: ConfiguredAccount,
        result: com.opentokenusage.android.data.network.clients.ProbeResult,
        nowMs: Long,
    ) {
        val provider = ProviderId.fromId(account.providerId)
        val snap = ProviderSnapshot(
            accountId = account.id,
            providerId = account.providerId,
            displayName = provider?.displayName ?: account.providerId,
            accountLabel = account.label,
            plan = result.plan,
            lines = result.lines,
            fetchedAtMs = nowMs,
            errorMessage = null,
            isStale = false,
        )

        val currentMap = _snapshots.value.toMutableMap()
        val previous = currentMap[account.id]
        currentMap[account.id] = snap

        _snapshots.value = currentMap
        stores.saveSnapshots(currentMap)

        val historyEntries = result.lines.filterIsInstance<MetricLine.Progress>().map { p ->
            UsageHistoryEntry(
                timestampMs = nowMs,
                lineLabel = p.label,
                usedPercent = p.usedFraction * 100.0,
            )
        }
        if (historyEntries.isNotEmpty()) {
            stores.appendHistory(account.id, historyEntries)
            _history.value = stores.loadHistory()
        }

        val currentAlerts = stores.loadAlertState()
        var updatedDedupe = notificationHelper.evaluateAndNotify(
            previous = previous,
            current = snap,
            settings = _settings.value,
            dedupeMap = currentAlerts.lastAlertKeys,
            nowMs = nowMs,
        )
        if (updatedDedupe != currentAlerts.lastAlertKeys) {
            stores.saveAlertState(AlertDedupeState(updatedDedupe))
        }
    }

    private suspend fun markErrorStale(account: ConfiguredAccount, errorMsg: String, nowMs: Long) {
        val currentMap = _snapshots.value.toMutableMap()
        val existing = currentMap[account.id]
        val provider = ProviderId.fromId(account.providerId)
        currentMap[account.id] = existing?.copy(
            errorMessage = errorMsg,
            isStale = existing.lines.isNotEmpty(),
        ) ?: ProviderSnapshot(
            accountId = account.id,
            providerId = account.providerId,
            displayName = provider?.displayName ?: account.providerId,
            accountLabel = account.label,
            lines = emptyList(),
            fetchedAtMs = nowMs,
            errorMessage = errorMsg,
            isStale = false,
        )
        _snapshots.value = currentMap
        stores.saveSnapshots(currentMap)
    }

    private suspend fun maybeRefreshFxRates() {
        val currency = _settings.value.displayCurrency
        if (currency == "USD" || _settings.value.manualUsdRate != null) return
        val cache = stores.loadFxCache()
        val now = System.currentTimeMillis()
        if (cache.rates.isNotEmpty() && now - cache.updatedAtMs < 12L * 3600_000L) return

        val resp = runCatching {
            ProviderHttpSupport.get(httpClient, "https://open.er-api.com/v6/latest/USD")
        }.getOrNull() ?: return
        if (resp.statusCode !in 200..299) return
        val ratesObj = ProviderHttpSupport.tryParseObject(resp.bodyText)?.objOrNull("rates") ?: return
        val parsed = mutableMapOf<String, Double>()
        for (key in ratesObj.keys) {
            ratesObj.doubleOrNull(key)?.takeIf { it > 0.0 }?.let { parsed[key] = it }
        }
        if (parsed.isNotEmpty()) {
            _fxRates.value = parsed
            stores.saveFxCache(FxRateCache(parsed, now))
        }
    }

    /** Returns enabled accounts in their configured display order. */
    fun orderedDisplaySnapshots(): List<ProviderSnapshot> {
        val accountsList = _accounts.value.sortedBy { it.sortOrder }.filter { it.enabled }
        val snapMap = _snapshots.value
        return accountsList.map { account ->
            val provider = ProviderId.fromId(account.providerId)
            snapMap[account.id] ?: ProviderSnapshot(
                accountId = account.id,
                providerId = account.providerId,
                displayName = provider?.displayName ?: account.providerId,
                accountLabel = account.label,
                fetchedAtMs = 0L,
            )
        }
    }

    fun refreshAsync(isManual: Boolean) {
        scope.launch { refreshAll(isManual = isManual) }
    }

    suspend fun updateWidgets() {
        runCatching { UsageOverviewWidget().updateAll(context.applicationContext) }
        runCatching { UsageSingleWidget().updateAll(context.applicationContext) }
        runCatching { UsageStatusStripWidget().updateAll(context.applicationContext) }
        runCatching { UsageMiniWidget().updateAll(context.applicationContext) }
        runCatching {
            notificationHelper.updatePersistentNotification(orderedDisplaySnapshots(), _settings.value)
        }
        UsageQuickSettingsTileService.requestListeningState(context)
    }

    private companion object {
        val LEGACY_DESKTOP_PROVIDER_IDS = setOf("desktop-sync", "jetbrains-ai-assistant")
    }
}
