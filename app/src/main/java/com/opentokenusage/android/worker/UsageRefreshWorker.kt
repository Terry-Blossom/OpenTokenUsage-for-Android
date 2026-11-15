package com.opentokenusage.android.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentokenusage.android.OpenTokenUsageApp
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import java.util.concurrent.TimeUnit

class UsageRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OpenTokenUsageApp ?: return Result.failure()
        val repo = app.container.repository
        repo.initialize()
        if (repo.accounts.value.none { it.enabled }) {
            repo.updateWidgets()
            return Result.success()
        }
        val ok = repo.refreshAll(isManual = false)
        return if (ok) Result.success() else Result.retry()
    }
}

object UsageRefreshScheduler {
    private const val WORK_NAME = "open_token_usage_periodic_sync"

    fun syncSchedule(context: Context, settings: AppSettings, hasAccounts: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val intervalMinutes = when (settings.refreshInterval) {
            RefreshIntervalOption.MANUAL -> null
            RefreshIntervalOption.MIN_5, RefreshIntervalOption.MIN_15, RefreshIntervalOption.SMART -> 15L
            RefreshIntervalOption.MIN_30 -> 30L
            RefreshIntervalOption.HOUR_1 -> 60L
        }
        if (!hasAccounts || intervalMinutes == null) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
