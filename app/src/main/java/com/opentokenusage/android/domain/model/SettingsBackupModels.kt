package com.opentokenusage.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Non-secret account entry included in settings export/import JSON.
 * Never includes credentials, API keys, OAuth tokens, cookies, or OAuth client secrets.
 */
@Serializable
data class ExportedAccount(
    val id: String = "",
    val providerId: String,
    val label: String = "",
    val enabled: Boolean = true,
    val region: MiniMaxRegion = MiniMaxRegion.GLOBAL,
    val customHost: String = "",
    val sortOrder: Int = 0,
    val alertThresholds: AccountAlertThresholds? = null,
)

/**
 * Versioned settings backup document containing AppSettings and non-secret account metadata.
 */
@Serializable
data class SettingsBackupPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtMs: Long = 0L,
    val settings: AppSettings = AppSettings(),
    val accounts: List<ExportedAccount> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class SettingsBackupImportSummary(
    val schemaVersion: Int,
    val importedAccountCount: Int,
    val updatedAccountCount: Int,
    val addedAccountCount: Int,
)
