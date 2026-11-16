package com.opentokenusage.android

import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CredentialInputParserTest {

    @Test
    fun extractsClaudeCredentialsJson() {
        val jsonText = """
            {
              "claudeAiOauth": {
                "accessToken": "sk-ant-oat01-access",
                "refreshToken": "sk-ant-ort01-refresh",
                "expiresAt": 1900000000000
              }
            }
        """.trimIndent()
        val parsed = CredentialInputParser.parse(ProviderId.CLAUDE, jsonText)
        assertEquals("sk-ant-oat01-access", parsed.secret.accessToken)
        assertEquals("sk-ant-ort01-refresh", parsed.secret.refreshToken)
        assertEquals(1900000000000L, parsed.secret.expiresAtMs)
        assertNotNull(parsed.detectedNote)
    }

    @Test
    fun extractsCodexAuthJson() {
        val jsonText = """
            {
              "tokens": {
                "access_token": "eyJhbGciOiJub25lIn0.eyJleHAiOjE5MDAwMDAwMDB9.sig",
                "refresh_token": "rt_codex_123",
                "account_id": "acc_999"
              }
            }
        """.trimIndent()
        val parsed = CredentialInputParser.parse(ProviderId.CODEX, jsonText)
        assertEquals("rt_codex_123", parsed.secret.refreshToken)
        assertEquals("acc_999", parsed.secret.accountId)
        assertEquals(1900000000_000L, parsed.secret.expiresAtMs)
    }

    @Test
    fun extractsCursorCookieSessionToken() {
        val cookie = "WorkosCursorSessionToken=user_abc123%3A%3AeyJtoken"
        val parsed = CredentialInputParser.parse(ProviderId.CURSOR, cookie)
        assertEquals("eyJtoken", parsed.secret.accessToken)
        assertEquals("user_abc123%3A%3AeyJtoken", parsed.secret.sessionCookie)
    }

    @Test
    fun recognizesCursorApiKeysForCursorAndGrok() {
        for (provider in listOf(ProviderId.CURSOR, ProviderId.GROK)) {
            val parsed = CredentialInputParser.parse(provider, "crsr_test_key")
            assertEquals("crsr_test_key", parsed.secret.apiKey)
            assertEquals("Cursor API キーを認識しました", parsed.detectedNote)
        }
    }

    @Test
    fun extractsDevinTomlCredentials() {
        val toml = """
            windsurf_api_key = "sk-ws-devin-123"
            api_server_url = "https://server.codeium.com"
        """.trimIndent()
        val parsed = CredentialInputParser.parse(ProviderId.DEVIN, toml)
        assertEquals("sk-ws-devin-123", parsed.secret.apiKey)
        assertEquals("https://server.codeium.com", parsed.secret.apiServerUrl)
    }

    @Test
    fun extractsAntigravityAccountsJsonAndPackedRefreshToken() {
        val jsonText = """
            {
              "version": 3,
              "accounts": [
                {
                  "email": "user@example.com",
                  "refreshToken": "1//0gToken|proj-alpha|managed-beta"
                }
              ]
            }
        """.trimIndent()
        val parsedJson = CredentialInputParser.parse(ProviderId.ANTIGRAVITY, jsonText)
        assertEquals("1//0gToken", parsedJson.secret.refreshToken)
        assertEquals("managed-beta", parsedJson.secret.accountId)

        val parsedRaw = CredentialInputParser.parse(ProviderId.ANTIGRAVITY, "1//0gDirect|proj-gamma")
        assertEquals("1//0gDirect", parsedRaw.secret.refreshToken)
        assertEquals("proj-gamma", parsedRaw.secret.accountId)
    }
}
