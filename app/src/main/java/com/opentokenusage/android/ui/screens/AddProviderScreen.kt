package com.opentokenusage.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MiniMaxRegion
import com.opentokenusage.android.domain.model.ProviderAuthMode
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.ui.theme.ProviderVisuals
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderScreen(
    preselectedProviderId: String?,
    editingAccount: ConfiguredAccount?,
    initialSecret: AccountSecret?,
    onTestAndSaveNew: suspend (ProviderId, String, MiniMaxRegion, String, AccountSecret, Boolean) -> Result<ConfiguredAccount>,
    onUpdateExisting: suspend (String, String, MiniMaxRegion, String, AccountSecret?) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalOtuColors.current
    var selectedProvider by remember(preselectedProviderId, editingAccount) {
        mutableStateOf(
            editingAccount?.let { ProviderId.fromId(it.providerId) }
                ?: preselectedProviderId?.let { ProviderId.fromId(it) },
        )
    }
    var searchQuery by remember { mutableStateOf("") }

    if (selectedProvider == null) {
        val filtered = remember(searchQuery) {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) ProviderId.entries else ProviderId.entries.filter {
                it.displayName.lowercase().contains(q) || it.id.lowercase().contains(q)
            }
        }
        Scaffold(
            containerColor = colors.background,
            topBar = {
                TopAppBar(
                    title = { Text("プロバイダーを追加", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = colors.textPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 14.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("プロバイダーを検索（例: Claude, OpenRouter, Codex）", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { p ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { selectedProvider = p },
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
                                    painter = painterResource(ProviderVisuals.iconRes(p.id)),
                                    contentDescription = p.displayName,
                                    tint = ProviderVisuals.iconTint(p.id, colors.isDark),
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.displayName, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    val sub = when (p.authMode) {
                                        ProviderAuthMode.API_KEY -> "APIキーで接続"
                                        ProviderAuthMode.OAUTH_OR_JSON -> "OAuthトークン / 認証JSON"
                                        ProviderAuthMode.SESSION_OR_OAUTH -> "Access Token / Session Cookie"
                                    }
                                    Text(sub, color = colors.textMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        val provider = selectedProvider!!
        ProviderCredentialForm(
            provider = provider,
            editingAccount = editingAccount,
            initialSecret = initialSecret,
            onBack = {
                if (editingAccount != null || preselectedProviderId != null) onBack() else selectedProvider = null
            },
            onSave = onTestAndSaveNew,
            onUpdate = onUpdateExisting,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCredentialForm(
    provider: ProviderId,
    editingAccount: ConfiguredAccount?,
    initialSecret: AccountSecret?,
    onBack: () -> Unit,
    onSave: suspend (ProviderId, String, MiniMaxRegion, String, AccountSecret, Boolean) -> Result<ConfiguredAccount>,
    onUpdate: suspend (String, String, MiniMaxRegion, String, AccountSecret?) -> Unit,
) {
    val colors = LocalOtuColors.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var alias by remember(editingAccount) { mutableStateOf(editingAccount?.label.orEmpty()) }
    var primaryInput by remember(initialSecret) {
        mutableStateOf(
            when {
                !initialSecret?.apiKey.isNullOrBlank() -> initialSecret?.apiKey.orEmpty()
                else -> initialSecret?.accessToken.orEmpty()
            },
        )
    }
    var refreshTokenInput by remember(initialSecret) { mutableStateOf(initialSecret?.refreshToken.orEmpty()) }
    var accountIdOrArn by remember(initialSecret) {
        mutableStateOf(initialSecret?.accountId?.ifBlank { initialSecret.profileArn }.orEmpty())
    }
    var awsRegion by remember(initialSecret) { mutableStateOf(initialSecret?.awsRegion?.ifBlank { "us-east-1" } ?: "us-east-1") }
    var customHost by remember(editingAccount) {
        mutableStateOf(
            editingAccount?.customHost.orEmpty().ifBlank {
                if (provider == ProviderId.COPILOT) "github.com" else ""
            },
        )
    }
    var region by remember(editingAccount) { mutableStateOf(editingAccount?.region ?: MiniMaxRegion.GLOBAL) }
    var detectedHint by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(primaryInput) {
        val trimmed = primaryInput.trim()
        if (trimmed.startsWith("{") || trimmed.contains("%3A%3A") || (provider == ProviderId.DEVIN && trimmed.contains("="))) {
            val parsed = CredentialInputParser.parse(provider, trimmed, refreshTokenInput, accountIdOrArn, awsRegion)
            detectedHint = parsed.detectedNote
        } else {
            detectedHint = null
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            painter = painterResource(ProviderVisuals.iconRes(provider.id)),
                            contentDescription = null,
                            tint = ProviderVisuals.iconTint(provider.id, colors.isDark),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (editingAccount == null) "${provider.displayName} を追加" else "${provider.displayName} を編集",
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (provider.localFileHint.isNotBlank() || provider.keyHelpUrl.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.card,
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "取得元の目安",
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val guide = when (provider) {
                            ProviderId.CLAUDE ->
                                "PCの ${provider.localFileHint} の中身（JSON全体）をそのまま貼り付けると Access / Refresh Token を自動抽出します。"
                            ProviderId.CODEX ->
                                "PCの ${provider.localFileHint} の中身（JSON全体）をそのまま貼り付けると tokens を自動抽出します。"
                            else -> provider.localFileHint.ifBlank { "公式コンソールから API キーを取得して入力してください。" }
                        }
                        Text(guide, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        if (provider.keyHelpUrl.isNotBlank()) {
                            OutlinedButton(
                                onClick = { runCatching { uriHandler.openUri(provider.keyHelpUrl) } },
                                shape = RoundedCornerShape(7.dp),
                                border = BorderStroke(1.dp, colors.border),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("取得ページを開く", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("アカウント名（任意・複数アカウント識別用）") },
                placeholder = { Text("例: 仕事用 / 個人") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (provider.supportsCustomHost) {
                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = {
                        Text("ホスト名")
                    },
                    placeholder = {
                        Text("github.com")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (provider.supportsRegion) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMaxRegion.entries.forEach { reg ->
                        FilterChip(
                            selected = region == reg,
                            onClick = { region = reg },
                            label = { Text(reg.label, fontSize = 12.sp) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (provider.authMode) {
                            ProviderAuthMode.API_KEY -> "API キー"
                            else -> "トークン または 認証JSON"
                        },
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            val text = clipboard.getText()?.text.orEmpty()
                            if (text.isNotBlank()) primaryInput = text
                        },
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("貼り付け", fontSize = 11.sp)
                    }
                }

                OutlinedTextField(
                    value = primaryInput,
                    onValueChange = { primaryInput = it },
                    placeholder = {
                        Text(
                            "sk-... / Bearer Token / 認証JSON全体",
                            fontSize = 12.sp,
                        )
                    },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (detectedHint != null) {
                Text(
                    text = detectedHint!!,
                    color = colors.paceGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (provider.authMode == ProviderAuthMode.OAUTH_OR_JSON || provider.authMode == ProviderAuthMode.SESSION_OR_OAUTH) {
                OutlinedTextField(
                    value = refreshTokenInput,
                    onValueChange = { refreshTokenInput = it },
                    label = { Text("Refresh Token（任意・JSON貼付時は自動抽出）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (provider == ProviderId.CODEX || provider == ProviderId.KIRO) {
                OutlinedTextField(
                    value = accountIdOrArn,
                    onValueChange = { accountIdOrArn = it },
                    label = { Text(if (provider == ProviderId.KIRO) "profileArn（任意）" else "ChatGPT Account ID（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!errorMsg.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.warningBg,
                    border = BorderStroke(1.dp, colors.warningBorder),
                ) {
                    Text(
                        text = errorMsg!!,
                        color = colors.paceRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                onClick = {
                    scope.launch {
                        errorMsg = null
                        val trimmedHost = customHost.trim()
                        if (primaryInput.isBlank() &&
                            refreshTokenInput.isBlank()
                        ) {
                            errorMsg = "APIキーまたは認証トークンを入力してください"
                            return@launch
                        }

                        isSubmitting = true
                        try {
                            val parsed = CredentialInputParser.parse(
                                providerId = provider,
                                primaryInput = primaryInput,
                                secondaryRefreshToken = refreshTokenInput,
                                accountIdOrArn = accountIdOrArn,
                                awsRegion = awsRegion,
                            )
                            if (editingAccount == null) {
                                val res = onSave(provider, alias, region, trimmedHost, parsed.secret, true)
                                if (res.isSuccess) {
                                    onBack()
                                } else {
                                    errorMsg = res.exceptionOrNull()?.message ?: "接続テストに失敗しました"
                                }
                            } else {
                                onUpdate(editingAccount.id, alias, region, trimmedHost, parsed.secret)
                                onBack()
                            }
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.textPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("接続を確認中...")
                } else {
                    Text(if (editingAccount == null) "接続テストして追加" else "保存して再取得", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
