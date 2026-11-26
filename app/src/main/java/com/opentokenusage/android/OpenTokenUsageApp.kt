package com.opentokenusage.android

import android.app.Application
import com.opentokenusage.android.data.auth.AntigravityOAuthClient
import com.opentokenusage.android.data.auth.AntigravityOAuthCoordinator
import com.opentokenusage.android.data.auth.AntigravityOAuthSettingsStore
import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.clients.ProviderClientRegistry
import com.opentokenusage.android.data.repository.UsageRepository
import com.opentokenusage.android.data.storage.AccountSecretsStore
import com.opentokenusage.android.data.storage.LocalAppStores
import com.opentokenusage.android.data.storage.RateLimitGateStore
import com.opentokenusage.android.data.update.AppUpdater
import com.opentokenusage.android.util.NotificationHelper
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(application: Application) {
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val stores by lazy { LocalAppStores(application, ProviderHttpSupport.json) }
    val secretsStore by lazy { AccountSecretsStore(application, ProviderHttpSupport.json) }
    val gateStore by lazy { RateLimitGateStore(application, ProviderHttpSupport.json) }
    val notificationHelper by lazy { NotificationHelper(application) }
    val appUpdater by lazy { AppUpdater(application, httpClient) }
    val antigravityOAuthSettings by lazy {
        AntigravityOAuthSettingsStore(
            secretsStore = secretsStore,
            defaultClientId = BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_ID,
            defaultClientSecret = BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_SECRET,
        )
    }
    val clientRegistry by lazy {
        ProviderClientRegistry(
            httpClient = httpClient,
            antigravityOAuthClientIdProvider = { antigravityOAuthSettings.current().clientId },
            antigravityOAuthClientSecretProvider = { antigravityOAuthSettings.current().clientSecret },
        )
    }
    val antigravityOAuthClient by lazy {
        AntigravityOAuthClient(
            http = httpClient,
            clientIdProvider = { antigravityOAuthSettings.current().clientId },
            clientSecretProvider = { antigravityOAuthSettings.current().clientSecret },
        )
    }
    val antigravityOAuthCoordinator by lazy { AntigravityOAuthCoordinator(application, antigravityOAuthClient) }

    val repository by lazy {
        UsageRepository(
            context = application,
            httpClient = httpClient,
            stores = stores,
            secretsStore = secretsStore,
            antigravityOAuthSettings = antigravityOAuthSettings,
            gateStore = gateStore,
            clientRegistry = clientRegistry,
            notificationHelper = notificationHelper,
        )
    }
}

class OpenTokenUsageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationHelper.ensureChannels()
    }
}
