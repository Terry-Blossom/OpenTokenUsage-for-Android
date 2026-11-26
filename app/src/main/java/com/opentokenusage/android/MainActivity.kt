package com.opentokenusage.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentokenusage.android.ui.theme.LocalOtuColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentokenusage.android.data.auth.AntigravityOAuthClientSettings
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.RefreshIntervalOption
import com.opentokenusage.android.domain.model.ResetTimerMode
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.ui.screens.AddProviderScreen
import com.opentokenusage.android.ui.screens.OverviewScreen
import com.opentokenusage.android.ui.screens.ProviderDetailScreen
import com.opentokenusage.android.ui.screens.SettingsScreen
import com.opentokenusage.android.ui.theme.OpenTokenUsageTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenTokenUsageApp).container
        val repo = container.repository
        val antigravityOAuth = container.antigravityOAuthCoordinator

        setContent {
            val navController = rememberNavController()
            val scope = rememberCoroutineScope()

            val accounts by repo.accounts.collectAsStateWithLifecycle()
            val antigravityOAuthSettings by container.antigravityOAuthSettings.settings.collectAsStateWithLifecycle()
            val snapshots by repo.snapshots.collectAsStateWithLifecycle()
            val settings by repo.settings.collectAsStateWithLifecycle()
            val history by repo.history.collectAsStateWithLifecycle()
            val fxRates by repo.fxRates.collectAsStateWithLifecycle()
            val isRefreshing by repo.isRefreshing.collectAsStateWithLifecycle()
            val lastSyncMs by repo.lastSyncMs.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                repo.initialize()
                val interval = repo.settings.value.refreshInterval
                val last = repo.lastSyncMs.value
                val minStaleMs = (interval.minutes ?: Int.MAX_VALUE) * 60_000L
                val needsSync = interval != RefreshIntervalOption.MANUAL &&
                    repo.accounts.value.any { it.enabled } &&
                    (last == null || System.currentTimeMillis() - last >= minStaleMs)
                if (needsSync) {
                    repo.refreshAll(isManual = false)
                }
            }

            val displaySnapshots = remember(accounts, snapshots) { repo.orderedDisplaySnapshots() }

            OpenTokenUsageTheme(settings.themeMode) {
                val colors = LocalOtuColors.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .systemBarsPadding(),
                ) {
                    NavHost(navController = navController, startDestination = "overview") {
                        composable("overview") {
                            OverviewScreen(
                                accounts = accounts,
                                displaySnapshots = displaySnapshots,
                                settings = settings,
                                fxRates = fxRates,
                                isRefreshing = isRefreshing,
                                lastSyncMs = lastSyncMs,
                                onRefreshAll = { repo.refreshAsync(isManual = true) },
                                onAddProvider = { preselected ->
                                    val route = if (preselected != null) {
                                        "add?providerId=$preselected"
                                    } else {
                                        "add"
                                    }
                                    navController.navigate(route)
                                },
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenDetail = { accId -> navController.navigate("detail/$accId") },
                                onToggleDisplayMode = {
                                    scope.launch {
                                        repo.updateSettings { s ->
                                            val next = if (s.displayMode == UsageDisplayMode.LEFT) UsageDisplayMode.USED else UsageDisplayMode.LEFT
                                            s.copy(displayMode = next)
                                        }
                                    }
                                },
                                onToggleResetMode = {
                                    scope.launch {
                                        repo.updateSettings { s ->
                                            val next = if (s.resetTimerMode == ResetTimerMode.RELATIVE) ResetTimerMode.ABSOLUTE else ResetTimerMode.RELATIVE
                                            s.copy(resetTimerMode = next)
                                        }
                                    }
                                },
                            )
                        }

                        composable(
                            route = "add?providerId={providerId}&editAccountId={editAccountId}",
                            arguments = listOf(
                                navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null },
                                navArgument("editAccountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            val preselected = entry.arguments?.getString("providerId")
                            val editId = entry.arguments?.getString("editAccountId")
                            val editingAccount = remember(accounts, editId) {
                                accounts.firstOrNull { it.id == editId }
                            }
                            var loadedSecret by remember(editId) { mutableStateOf<AccountSecret?>(null) }
                            LaunchedEffect(editId) {
                                if (editId != null) loadedSecret = repo.getSecret(editId)
                            }
                            AddProviderScreen(
                                preselectedProviderId = preselected,
                                editingAccount = editingAccount,
                                initialSecret = loadedSecret,
                                antigravityOAuth = antigravityOAuth,
                                antigravityOAuthSettings = antigravityOAuthSettings,
                                onSaveAntigravityOAuthSettings = { id, secret ->
                                    container.antigravityOAuthSettings.save(id, secret)
                                },
                                onTestAndSaveNew = { pid, label, region, host, secret, verify ->
                                    repo.addAccountAndProbe(pid, label, region, host, secret, verify)
                                },
                                onUpdateExisting = { id, label, region, host, sec ->
                                    repo.updateAccountCredentials(id, label, region, host, sec)
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(
                            route = "detail/{accountId}",
                            arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
                        ) { entry ->
                            val accountId = entry.arguments?.getString("accountId").orEmpty()
                            val snapshot = displaySnapshots.firstOrNull { it.accountId == accountId }
                                ?: snapshots[accountId]
                            val ownedAccount = accounts.firstOrNull { it.id == accountId }

                            ProviderDetailScreen(
                                snapshot = snapshot,
                                history = history[accountId].orEmpty(),
                                settings = settings,
                                fxRates = fxRates,
                                isOwnedAccount = ownedAccount != null,
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    scope.launch { repo.refreshAccount(accountId, isManual = true) }
                                },
                                onEdit = {
                                    if (ownedAccount != null) {
                                        navController.navigate("add?editAccountId=${ownedAccount.id}")
                                    }
                                },
                                onDelete = {
                                    scope.launch { repo.deleteAccount(accountId) }
                                },
                                onToggleResetMode = {
                                    scope.launch {
                                        repo.updateSettings { s ->
                                            val next = if (s.resetTimerMode == ResetTimerMode.RELATIVE) ResetTimerMode.ABSOLUTE else ResetTimerMode.RELATIVE
                                            s.copy(resetTimerMode = next)
                                        }
                                    }
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                settings = settings,
                                accounts = accounts,
                                appUpdater = container.appUpdater,
                                onUpdateSettings = { transform ->
                                    scope.launch { repo.updateSettings(transform) }
                                },
                                onToggleAccountEnabled = { id, enabled ->
                                    scope.launch { repo.setAccountEnabled(id, enabled) }
                                },
                                onMoveAccount = { id, dir ->
                                    scope.launch { repo.moveAccount(id, dir) }
                                },
                                onDeleteAccount = { id ->
                                    scope.launch { repo.deleteAccount(id) }
                                },
                                onEditAccount = { id ->
                                    navController.navigate("add?editAccountId=$id")
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
