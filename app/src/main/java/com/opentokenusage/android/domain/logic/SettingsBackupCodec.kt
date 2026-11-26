package com.opentokenusage.android.domain.logic

import com.opentokenusage.android.domain.model.AccountAlertThresholds
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.CustomBalanceConfig
import com.opentokenusage.android.domain.model.ExportedAccount
import com.opentokenusage.android.domain.model.SettingsBackupImportSummary
import com.opentokenusage.android.domain.model.SettingsBackupPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import java.util.UUID

data class MergedBackupState(
    val settings: AppSettings,
    val accounts: List<ConfiguredAccount>,
    val summary: SettingsBackupImportSummary,
)

/**
 * Encodes and decodes versioned, secret-free settings backup JSON for SAF export/import.
 */
object SettingsBackupCodec {
    private val exportFormatter = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val importFormatter = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun exportJson(
        settings: AppSettings,
        accounts: List<ConfiguredAccount>,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val sortedAccounts = accounts.sortedBy { it.sortOrder }
        val exportedIds = sortedAccounts.map { it.id }.toSet()
        val cleanThresholdMap = settings.accountAlertThresholds
            .filterKeys { it in exportedIds }
            .mapValues { (_, t) -> sanitizeThresholds(t) }
            .filterValues { it != null && !it.isEmpty() }
            .mapValues { (_, t) -> t!! }

        val exportedAccounts = sortedAccounts.mapIndexed { idx, acc ->
            ExportedAccount(
                id = acc.id,
                providerId = acc.providerId.trim().lowercase(Locale.ROOT),
                label = acc.label.trim(),
                enabled = acc.enabled,
                region = acc.region,
                customHost = acc.customHost.trim(),
                sortOrder = idx,
                alertThresholds = cleanThresholdMap[acc.id],
                customBalance = sanitizeCustomBalance(acc.customBalance),
            )
        }
        val payload = SettingsBackupPayload(
            schemaVersion = SettingsBackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAtMs = exportedAtMs,
            settings = sanitizeAppSettings(settings.copy(accountAlertThresholds = cleanThresholdMap)),
            accounts = exportedAccounts,
        )
        return exportFormatter.encodeToString(payload)
    }

    fun importJson(rawJson: String): Result<SettingsBackupPayload> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("設定ファイルが空です。"))
        }
        val rootObj = runCatching { importFormatter.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("JSON の形式が正しくありません。"))

        val versionPrim = rootObj["schemaVersion"] as? JsonPrimitive
        val version = versionPrim?.intOrNull
            ?: return Result.failure(IllegalArgumentException("設定バックアップのバージョン情報 (schemaVersion) が見つかりません。"))
        if (version < 1) {
            return Result.failure(IllegalArgumentException("不正なバックアップバージョンです (v$version)。"))
        }
        if (version > SettingsBackupPayload.CURRENT_SCHEMA_VERSION) {
            return Result.failure(
                IllegalArgumentException(
                    "このファイルは新しいバージョン (v$version) で作成されています。アプリを更新してから読み込んでください。",
                ),
            )
        }

        val decoded = runCatching { importFormatter.decodeFromString<SettingsBackupPayload>(trimmed) }
            .getOrElse { e ->
                return Result.failure(IllegalArgumentException("設定の読み込みに失敗しました: ${e.message ?: "形式エラー"}"))
            }

        val cleanedAccounts = decoded.accounts
            .mapNotNull { acc ->
                val normalizedProvider = acc.providerId.trim().lowercase(Locale.ROOT)
                if (normalizedProvider.isEmpty()) {
                    null
                } else {
                    acc.copy(
                        id = acc.id.trim(),
                        providerId = normalizedProvider,
                        label = acc.label.trim(),
                        customHost = acc.customHost.trim(),
                        alertThresholds = sanitizeThresholds(acc.alertThresholds),
                        customBalance = sanitizeCustomBalance(acc.customBalance),
                    )
                }
            }

        return Result.success(
            decoded.copy(
                schemaVersion = version,
                settings = sanitizeAppSettings(decoded.settings),
                accounts = cleanedAccounts,
            ),
        )
    }

    /**
     * Merges a parsed backup into current local accounts and settings without touching secrets.
     * Existing accounts matched by ID or (providerId, label) keep their local IDs so encrypted
     * credentials stay linked; local-only accounts not present in the backup are preserved.
     */
    fun mergeBackup(
        currentAccounts: List<ConfiguredAccount>,
        currentSettings: AppSettings,
        backup: SettingsBackupPayload,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
        nowMs: Long = System.currentTimeMillis(),
    ): MergedBackupState {
        val remainingExisting = currentAccounts.sortedBy { it.sortOrder }.toMutableList()
        val usedIds = mutableSetOf<String>()
        val resultAccounts = mutableListOf<ConfiguredAccount>()
        val mergedThresholds = mutableMapOf<String, AccountAlertThresholds>()

        var updatedCount = 0
        var addedCount = 0

        for (exported in backup.accounts.sortedBy { it.sortOrder }) {
            val matchedIndex = findMatchingExistingIndex(remainingExisting, exported)
            val resolvedAccount: ConfiguredAccount
            if (matchedIndex >= 0) {
                val existing = remainingExisting.removeAt(matchedIndex)
                resolvedAccount = existing.copy(
                    providerId = exported.providerId,
                    label = exported.label,
                    enabled = exported.enabled,
                    region = exported.region,
                    customHost = exported.customHost,
                    customBalance = exported.customBalance,
                )
                updatedCount++
            } else {
                val candidateId = exported.id.takeIf { it.isNotBlank() && it !in usedIds } ?: idGenerator()
                resolvedAccount = ConfiguredAccount(
                    id = candidateId,
                    providerId = exported.providerId,
                    label = exported.label,
                    enabled = exported.enabled,
                    region = exported.region,
                    customHost = exported.customHost,
                    sortOrder = 0,
                    createdAtMs = nowMs,
                    customBalance = exported.customBalance,
                )
                addedCount++
            }
            usedIds += resolvedAccount.id
            resultAccounts += resolvedAccount

            val threshold = sanitizeThresholds(
                exported.alertThresholds ?: backup.settings.accountAlertThresholds[exported.id],
            )
            if (threshold != null && !threshold.isEmpty()) {
                mergedThresholds[resolvedAccount.id] = threshold
            }
        }

        // Preserve any local-only accounts (and their thresholds) so secrets are never orphaned.
        for (unmatched in remainingExisting) {
            resultAccounts += unmatched
            currentSettings.accountAlertThresholds[unmatched.id]?.let { existingThreshold ->
                sanitizeThresholds(existingThreshold)?.takeUnless { it.isEmpty() }?.let {
                    mergedThresholds[unmatched.id] = it
                }
            }
        }

        val reindexedAccounts = resultAccounts.mapIndexed { idx, acc -> acc.copy(sortOrder = idx) }
        val mergedSettings = sanitizeAppSettings(
            backup.settings.copy(accountAlertThresholds = mergedThresholds),
        )

        return MergedBackupState(
            settings = mergedSettings,
            accounts = reindexedAccounts,
            summary = SettingsBackupImportSummary(
                schemaVersion = backup.schemaVersion,
                importedAccountCount = updatedCount + addedCount,
                updatedAccountCount = updatedCount,
                addedAccountCount = addedCount,
            ),
        )
    }

    private fun findMatchingExistingIndex(
        existing: List<ConfiguredAccount>,
        exported: ExportedAccount,
    ): Int {
        if (exported.id.isNotBlank()) {
            val byId = existing.indexOfFirst { it.id == exported.id }
            if (byId >= 0) return byId
        }
        return existing.indexOfFirst {
            it.providerId.equals(exported.providerId, ignoreCase = true) &&
                it.label.trim() == exported.label.trim()
        }
    }

    private fun sanitizeCustomBalance(config: CustomBalanceConfig?): CustomBalanceConfig? {
        if (config == null) return null
        return CustomBalanceConfig(
            preset = config.preset,
            userId = config.userId.trim(),
            path = config.path.trim(),
            authHeader = config.authHeader,
            remainingPath = config.remainingPath.trim(),
            usedPath = config.usedPath.trim(),
            totalPath = config.totalPath.trim(),
            divisor = config.divisor.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
            currency = config.currency.trim().uppercase(Locale.ROOT).ifEmpty { "USD" },
        )
    }

    private fun sanitizeThresholds(thresholds: AccountAlertThresholds?): AccountAlertThresholds? {
        if (thresholds == null) return null
        val cleaned = AccountAlertThresholds(
            threshold1 = thresholds.threshold1?.coerceIn(1, 99),
            threshold2 = thresholds.threshold2?.coerceIn(1, 99),
        )
        return cleaned.takeUnless { it.isEmpty() }
    }

    private fun sanitizeAppSettings(settings: AppSettings): AppSettings =
        settings.copy(
            displayCurrency = settings.displayCurrency.trim().uppercase(Locale.ROOT).ifEmpty { "USD" },
            manualUsdRate = settings.manualUsdRate?.takeIf { it.isFinite() && it > 0.0 },
            alertThreshold1 = settings.alertThreshold1.coerceIn(1, 99),
            alertThreshold2 = settings.alertThreshold2.coerceIn(1, 99),
            overviewMaxLines = settings.overviewMaxLines.coerceIn(1, 20),
            accountAlertThresholds = settings.accountAlertThresholds
                .mapValues { (_, t) -> sanitizeThresholds(t) }
                .filterValues { it != null && !it.isEmpty() }
                .mapValues { (_, t) -> t!! },
        )
}
