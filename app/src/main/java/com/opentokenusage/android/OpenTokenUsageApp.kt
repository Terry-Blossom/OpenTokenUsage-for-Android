package com.opentokenusage.android

import android.app.Application
import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.clients.ProviderClientRegistry
import com.opentokenusage.android.data.repository.UsageRepository
import com.opentokenusage.android.data.storage.AccountSecretsStore
import com.opentokenusage.android.data.storage.LocalAppStores
import com.opentokenusage.android.data.storage.RateLimitGateStore
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
    val clientRegistry by lazy { ProviderClientRegistry(httpClient) }

    val repository by lazy {
        UsageRepository(
            context = application,
            httpClient = httpClient,
            stores = stores,
            secretsStore = secretsStore,
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
