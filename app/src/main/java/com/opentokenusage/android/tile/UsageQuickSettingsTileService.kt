package com.opentokenusage.android.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.opentokenusage.android.OpenTokenUsageApp
import com.opentokenusage.android.R
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HighestPrimaryUsage(
    val snapshot: ProviderSnapshot,
    val primary: MetricLine.Progress,
)

internal data class QuickSettingsTilePresentation(
    val label: String,
    val subtitle: String,
    val active: Boolean,
)

internal fun selectPrimaryUsageLine(snapshot: ProviderSnapshot): MetricLine.Progress? {
    val overviewPrimary = snapshot.overviewLines()
        .filterIsInstance<MetricLine.Progress>()
        .minByOrNull { it.primaryOrder ?: Int.MAX_VALUE }
    return overviewPrimary ?: snapshot.primaryProgressLines(maxCount = 1).firstOrNull()
}

internal fun selectHighestPrimaryUsage(snapshots: List<ProviderSnapshot>): HighestPrimaryUsage? =
    snapshots.mapNotNull { snapshot ->
        selectPrimaryUsageLine(snapshot)?.let { primary ->
            HighestPrimaryUsage(snapshot = snapshot, primary = primary)
        }
    }.maxByOrNull { it.primary.usedFraction }

internal fun buildQuickSettingsTilePresentation(
    snapshots: List<ProviderSnapshot>,
    displayMode: UsageDisplayMode,
    isRefreshing: Boolean,
    tileLabel: String = "AI使用率",
): QuickSettingsTilePresentation {
    if (isRefreshing) {
        return QuickSettingsTilePresentation(
            label = tileLabel,
            subtitle = "更新中...",
            active = true,
        )
    }
    val highest = selectHighestPrimaryUsage(snapshots)
    if (highest != null) {
        val pct = UsageDisplayFormatter.formatPercentShort(
            used = highest.primary.used,
            limit = highest.primary.limit,
            mode = displayMode,
        )
        val subtitle = when (displayMode) {
            UsageDisplayMode.LEFT -> "${highest.snapshot.displayName} 残り $pct"
            UsageDisplayMode.USED -> "${highest.snapshot.displayName} $pct 使用"
        }
        return QuickSettingsTilePresentation(
            label = tileLabel,
            subtitle = subtitle,
            active = true,
        )
    }
    val emptySubtitle = if (snapshots.isEmpty()) "未接続" else "タップして更新"
    return QuickSettingsTilePresentation(
        label = tileLabel,
        subtitle = emptySubtitle,
        active = false,
    )
}

class UsageQuickSettingsTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            updateTileFromRepository()
        }
    }

    override fun onClick() {
        super.onClick()
        if (refreshJob?.isActive == true) return
        val app = applicationContext as? OpenTokenUsageApp ?: return
        val tileLabel = getString(R.string.qs_tile_label)
        applyPresentation(
            buildQuickSettingsTilePresentation(
                snapshots = emptyList(),
                displayMode = app.container.repository.settings.value.displayMode,
                isRefreshing = true,
                tileLabel = tileLabel,
            ),
        )
        refreshJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.container.repository.initialize()
                    app.container.repository.refreshAll(isManual = true)
                }
            } finally {
                updateTileFromRepository()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun updateTileFromRepository() {
        val app = applicationContext as? OpenTokenUsageApp ?: return
        val repo = app.container.repository
        val (snapshots, settings, refreshing) = withContext(Dispatchers.IO) {
            repo.initialize()
            Triple(
                repo.orderedDisplaySnapshots(),
                repo.settings.value,
                repo.isRefreshing.value,
            )
        }
        val presentation = buildQuickSettingsTilePresentation(
            snapshots = snapshots,
            displayMode = settings.displayMode,
            isRefreshing = refreshing,
            tileLabel = getString(R.string.qs_tile_label),
        )
        applyPresentation(presentation)
    }

    private fun applyPresentation(presentation: QuickSettingsTilePresentation) {
        val tile = qsTile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = presentation.label
            tile.subtitle = presentation.subtitle
        } else {
            tile.label = presentation.subtitle
        }
        tile.state = if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = "${presentation.label} ${presentation.subtitle}"
        tile.updateTile()
    }

    companion object {
        fun requestListeningState(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, UsageQuickSettingsTileService::class.java),
                )
            }
        }
    }
}
