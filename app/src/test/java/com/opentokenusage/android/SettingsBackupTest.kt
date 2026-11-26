package com.opentokenusage.android

import com.opentokenusage.android.data.auth.AntigravityOAuthClientSettings
import com.opentokenusage.android.domain.logic.SettingsBackupCodec
import com.opentokenusage.android.domain.model.AccountAlertThresholds
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.AppThemeMode
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MiniMaxRegion
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import com.opentokenusage.android.domain.model.ResetTimerMode
import com.opentokenusage.android.domain.model.SettingsBackupPayload
import com.opentokenusage.android.domain.model.UsageDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    @Test
    fun exportJsonExcludesAllSecretsAndCredentials() {
        val secret = AccountSecret(
            apiKey = "sk-live-super-secret-api-key-12345",
            accessToken = "ya29.secret-access-token-value",
            refreshToken = "1//secret-refresh-token-value",
            expiresAtMs = 1_900_000_000_000L,
            accountId = "acct_secret_internal",
            sessionCookie = "WorkosCursorSessionToken=secret-cookie-value",
            profileArn = "arn:aws:codewhisperer:us-east-1:123456789012:profile/SECRET",
            apiServerUrl = "https://secret-devin-server.internal",
        )
        val oauthClient = AntigravityOAuthClientSettings(
            clientId = "123456789-secretclient.apps.googleusercontent.com",
            clientSecret = "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cret",
        )
        val accounts = listOf(
            ConfiguredAccount(
                id = "acc-claude",
                providerId = "claude",
                label = "メイン",
                enabled = true,
                sortOrder = 0,
            ),
            ConfiguredAccount(
                id = "acc-cursor",
                providerId = "cursor",
                label = "仕事用",
                enabled = false,
                sortOrder = 1,
            ),
        )
        val settings = AppSettings(
            alertThreshold1 = 70,
            alertThreshold2 = 92,
            accountAlertThresholds = mapOf("acc-claude" to AccountAlertThresholds(60, 85)),
        )

        val exported = SettingsBackupCodec.exportJson(settings, accounts, exportedAtMs = 1_700_000_000_000L)

        // Ensure no secret values appear in exported JSON
        val forbiddenValues = listOf(
            secret.apiKey,
            secret.accessToken,
            secret.refreshToken,
            secret.sessionCookie,
            secret.profileArn,
            secret.apiServerUrl,
            oauthClient.clientId,
            oauthClient.clientSecret,
        )
        for (value in forbiddenValues) {
            assertFalse("Exported JSON must not contain secret value: $value", exported.contains(value))
        }

        // Ensure no secret field names appear in exported JSON
        val lowerJson = exported.lowercase()
        val forbiddenKeys = listOf(
            "\"apikey\"",
            "\"accesstoken\"",
            "\"refreshtoken\"",
            "\"sessioncookie\"",
            "\"clientid\"",
            "\"clientsecret\"",
            "\"password\"",
            "\"profilearn\"",
            "\"syncbearertoken\"",
            "\"apiserverurl\"",
        )
        for (key in forbiddenKeys) {
            assertFalse("Exported JSON must not contain secret key: $key", lowerJson.contains(key))
        }
    }

    @Test
    fun exportImportRoundTripMatchesSettingsAndAccounts() {
        val originalAccounts = listOf(
            ConfiguredAccount(
                id = "acc-1",
                providerId = "claude",
                label = "個人",
                enabled = true,
                region = MiniMaxRegion.GLOBAL,
                customHost = "",
                sortOrder = 0,
                createdAtMs = 0L,
            ),
            ConfiguredAccount(
                id = "acc-2",
                providerId = "minimax",
                label = "CN",
                enabled = false,
                region = MiniMaxRegion.CN,
                customHost = "",
                sortOrder = 1,
                createdAtMs = 0L,
            ),
            ConfiguredAccount(
                id = "acc-3",
                providerId = "copilot",
                label = "Enterprise",
                enabled = true,
                region = MiniMaxRegion.GLOBAL,
                customHost = "github.example.com",
                sortOrder = 2,
                createdAtMs = 0L,
            ),
        )
        val originalSettings = AppSettings(
            refreshInterval = RefreshIntervalOption.SMART,
            displayMode = UsageDisplayMode.USED,
            workdaysOnlyWeeklyPace = true,
            resetTimerMode = ResetTimerMode.ABSOLUTE,
            clock24h = false,
            themeMode = AppThemeMode.LIGHT,
            displayCurrency = "JPY",
            manualUsdRate = 152.5,
            alertThreshold1 = 65,
            alertThreshold2 = 88,
            accountAlertThresholds = mapOf(
                "acc-1" to AccountAlertThresholds(threshold1 = 55, threshold2 = 80),
                "acc-3" to AccountAlertThresholds(threshold1 = 70, threshold2 = null),
            ),
            notifyThresholdAlerts = false,
            notifyPaceAlerts = true,
            notifyResetAlerts = true,
            notifyResetExpiryAlerts = false,
            persistentNotificationEnabled = true,
            showMonthlyUsage = true,
            showPaceMarkers = false,
            overviewMaxLines = 6,
        )

        val exportedJson = SettingsBackupCodec.exportJson(
            settings = originalSettings,
            accounts = originalAccounts,
            exportedAtMs = 1_795_000_000_000L,
        )
        assertTrue(exportedJson.contains("\"schemaVersion\": ${SettingsBackupPayload.CURRENT_SCHEMA_VERSION}"))

        val parsed = SettingsBackupCodec.importJson(exportedJson).getOrThrow()
        assertEquals(SettingsBackupPayload.CURRENT_SCHEMA_VERSION, parsed.schemaVersion)

        val merged = SettingsBackupCodec.mergeBackup(
            currentAccounts = emptyList(),
            currentSettings = AppSettings(),
            backup = parsed,
            nowMs = 0L,
        )

        assertEquals(originalSettings, merged.settings)
        assertEquals(originalAccounts, merged.accounts)
        assertEquals(3, merged.summary.addedAccountCount)
        assertEquals(0, merged.summary.updatedAccountCount)

        val reExportedJson = SettingsBackupCodec.exportJson(
            settings = merged.settings,
            accounts = merged.accounts,
            exportedAtMs = 1_795_000_000_000L,
        )
        assertEquals(exportedJson, reExportedJson)
    }

    @Test
    fun importIgnoresUnknownKeysAndValidatesSchemaVersion() {
        val jsonWithUnknownKeys = """
            {
              "schemaVersion": 1,
              "exportedAtMs": 1700000000000,
              "futureTopLevelFlag": true,
              "settings": {
                "displayMode": "USED",
                "displayCurrency": "EUR",
                "unknownSettingKey": "ignored-value"
              },
              "accounts": [
                {
                  "id": "acc-x",
                  "providerId": "codex",
                  "label": "Work",
                  "enabled": true,
                  "sortOrder": 0,
                  "alertThresholds": { "threshold1": 60, "threshold2": 85, "extraNested": 123 },
                  "unknownAccountField": "should-be-ignored"
                }
              ]
            }
        """.trimIndent()

        val payload = SettingsBackupCodec.importJson(jsonWithUnknownKeys).getOrThrow()
        assertEquals(1, payload.schemaVersion)
        assertEquals(UsageDisplayMode.USED, payload.settings.displayMode)
        assertEquals("EUR", payload.settings.displayCurrency)
        assertEquals(1, payload.accounts.size)
        assertEquals("codex", payload.accounts[0].providerId)
        assertEquals(AccountAlertThresholds(60, 85), payload.accounts[0].alertThresholds)

        assertTrue(SettingsBackupCodec.importJson("").isFailure)
        assertTrue(SettingsBackupCodec.importJson("not-json").isFailure)
        assertTrue(SettingsBackupCodec.importJson("""{"settings":{}}""").isFailure)
        assertTrue(SettingsBackupCodec.importJson("""{"schemaVersion":0}""").isFailure)
        assertTrue(SettingsBackupCodec.importJson("""{"schemaVersion":99}""").isFailure)
    }

    @Test
    fun mergePreservesExistingAccountIdsAndLocalOnlyAccounts() {
        val existingAccounts = listOf(
            ConfiguredAccount(
                id = "local-claude-id",
                providerId = "claude",
                label = "メイン",
                enabled = true,
                sortOrder = 0,
                createdAtMs = 100L,
            ),
            ConfiguredAccount(
                id = "local-only-grok",
                providerId = "grok",
                label = "個人",
                enabled = true,
                sortOrder = 1,
                createdAtMs = 200L,
            ),
        )
        val existingSettings = AppSettings(
            accountAlertThresholds = mapOf(
                "local-only-grok" to AccountAlertThresholds(50, 75),
            ),
        )

        val backupJson = """
            {
              "schemaVersion": 1,
              "settings": { "showMonthlyUsage": true },
              "accounts": [
                {
                  "id": "",
                  "providerId": "claude",
                  "label": "メイン",
                  "enabled": false,
                  "sortOrder": 0,
                  "alertThresholds": { "threshold1": 65, "threshold2": 85 }
                },
                {
                  "id": "imported-cursor",
                  "providerId": "cursor",
                  "label": "サブ",
                  "enabled": true,
                  "sortOrder": 1
                }
              ]
            }
        """.trimIndent()

        val payload = SettingsBackupCodec.importJson(backupJson).getOrThrow()
        val merged = SettingsBackupCodec.mergeBackup(
            currentAccounts = existingAccounts,
            currentSettings = existingSettings,
            backup = payload,
            nowMs = 500L,
        )

        assertEquals(3, merged.accounts.size)
        // Matched existing Claude account keeps its local ID and createdAtMs while updating non-secret fields
        assertEquals("local-claude-id", merged.accounts[0].id)
        assertEquals(100L, merged.accounts[0].createdAtMs)
        assertFalse(merged.accounts[0].enabled)
        assertEquals(0, merged.accounts[0].sortOrder)

        // Newly added Cursor account
        assertEquals("imported-cursor", merged.accounts[1].id)
        assertEquals(1, merged.accounts[1].sortOrder)

        // Local-only Grok account and its threshold are preserved
        assertEquals("local-only-grok", merged.accounts[2].id)
        assertEquals(2, merged.accounts[2].sortOrder)
        assertEquals(AccountAlertThresholds(65, 85), merged.settings.accountAlertThresholds["local-claude-id"])
        assertEquals(AccountAlertThresholds(50, 75), merged.settings.accountAlertThresholds["local-only-grok"])
        assertTrue(merged.settings.showMonthlyUsage)
        assertEquals(1, merged.summary.updatedAccountCount)
        assertEquals(1, merged.summary.addedAccountCount)
    }
}
