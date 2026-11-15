package com.opentokenusage.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opentokenusage.android.MainActivity
import com.opentokenusage.android.OpenTokenUsageApp
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.ui.theme.OpenTokenUsageTheme
import com.opentokenusage.android.ui.theme.ProviderVisuals
import kotlinx.coroutines.launch

class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val repo = (application as OpenTokenUsageApp).container.repository

        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle()
            val accounts by repo.accounts.collectAsStateWithLifecycle()
            val snapshots by repo.snapshots.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                repo.initialize()
            }

            OpenTokenUsageTheme(settings.themeMode) {
                val colors = LocalOtuColors.current
                val displayItems = remember(accounts, snapshots) { repo.orderedDisplaySnapshots() }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .systemBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "ウィジェットに表示するアカウントを選択",
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    if (displayItems.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = colors.card,
                            border = BorderStroke(1.dp, colors.border),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "接続済みのプロバイダーがありません",
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "アプリを開いて APIキーまたは PC同期を追加してから、このウィジェットを配置してください。",
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                )
                                Button(
                                    onClick = {
                                        startActivity(Intent(this@WidgetConfigureActivity, MainActivity::class.java))
                                        finish()
                                    },
                                ) {
                                    Text("アプリを開いて追加")
                                }
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(displayItems, key = { it.accountId }) { item ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                repo.bindWidgetAccount(appWidgetId, item.accountId)
                                                val resIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                                setResult(RESULT_OK, resIntent)
                                                finish()
                                            }
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = colors.card,
                                    border = BorderStroke(1.dp, colors.border),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(ProviderVisuals.iconRes(item.providerId)),
                                            contentDescription = item.displayName,
                                            tint = ProviderVisuals.iconTint(item.providerId, colors.isDark),
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (item.accountLabel.isNotBlank()) {
                                                    "${item.displayName} (${item.accountLabel})"
                                                } else {
                                                    item.displayName
                                                },
                                                color = colors.textPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            if (!item.plan.isNullOrBlank()) {
                                                Text(item.plan, color = colors.textMuted, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
