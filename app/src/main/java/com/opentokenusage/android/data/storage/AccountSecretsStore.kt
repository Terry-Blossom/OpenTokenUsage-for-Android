package com.opentokenusage.android.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.opentokenusage.android.domain.model.AccountSecret
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores per-account API keys, OAuth tokens, and cookies inside Android Keystore-backed
 * EncryptedSharedPreferences. Starts completely empty on fresh installs.
 */
class AccountSecretsStore(
    context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun getSecret(accountId: String): AccountSecret? = mutex.withLock {
        val raw = prefs.getString(keyFor(accountId), null) ?: return@withLock null
        runCatching { json.decodeFromString<AccountSecret>(raw) }.getOrNull()
    }

    suspend fun saveSecret(accountId: String, secret: AccountSecret) = mutex.withLock {
        prefs.edit().putString(keyFor(accountId), json.encodeToString(secret)).apply()
    }

    suspend fun deleteSecret(accountId: String) = mutex.withLock {
        prefs.edit().remove(keyFor(accountId)).apply()
    }

    suspend fun updateTokens(
        accountId: String,
        newAccessToken: String,
        newRefreshToken: String? = null,
        newExpiresAtMs: Long? = null,
    ) = mutex.withLock {
        val existing = prefs.getString(keyFor(accountId), null)
            ?.let { runCatching { json.decodeFromString<AccountSecret>(it) }.getOrNull() }
            ?: AccountSecret()
        val updated = existing.copy(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken?.takeIf { it.isNotBlank() } ?: existing.refreshToken,
            expiresAtMs = newExpiresAtMs ?: existing.expiresAtMs,
        )
        prefs.edit().putString(keyFor(accountId), json.encodeToString(updated)).apply()
    }

    private fun keyFor(accountId: String): String = "secret_$accountId"

    companion object {
        private const val PREFS_NAME = "open_token_usage_secrets_enc"
    }
}
