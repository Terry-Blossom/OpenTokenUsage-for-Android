package com.opentokenusage.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opentokenusage.android.data.auth.AntigravityOAuthCoordinator
import com.opentokenusage.android.data.auth.AntigravityOAuthClientSettings
import com.opentokenusage.android.data.auth.AntigravityOAuthConfig
import com.opentokenusage.android.data.auth.AntigravityOAuthUiState
import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.CustomBalanceAuthHeader
import com.opentokenusage.android.domain.model.CustomBalanceConfig
import com.opentokenusage.android.domain.model.CustomBalancePreset
import com.opentokenusage.android.domain.model.MiniMaxRegion
import com.opentokenusage.android.domain.model.ProviderAuthMode
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.ui.theme.LocalOtuColors
import com.opentokenusage.android.ui.theme.ProviderVisuals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderScreen(
    preselectedProviderId: String?,
    editingAccount: ConfiguredAccount?,
    initialSecret: AccountSecret?,
    antigravityOAuth: AntigravityOAuthCoordinator? = null,
    antigravityOAuthSettings: AntigravityOAuthClientSettings = AntigravityOAuthClientSettings(),
    onSaveAntigravityOAuthSettings: suspend (String, String) -> Unit = { _, _ -> },
    onTestAndSaveNew: suspend (ProviderId, String, MiniMaxRegion, String, AccountSecret, Boolean, CustomBalanceConfig?) -> Result<ConfiguredAccount>,
    onUpdateExisting: suspend (String, String, MiniMaxRegion, String, AccountSecret?, CustomBalanceConfig?) -> Unit,
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
                                    val sub = when {
                                        p == ProviderId.ANTIGRAVITY -> "Google ログイン / OAuth トークン"
                                        p == ProviderId.CUSTOM_BALANCE -> "ベース URL・API キー（New API / Sub2API / 独自・複数登録可）"
                                        p == ProviderId.KIMI_API -> "Moonshot API キー（従量課金残高）"
                                        p == ProviderId.KILO || p == ProviderId.CODEBUFF -> "API キー / CLI 認証 JSON"
                                        p == ProviderId.CURSOR || p == ProviderId.GROK -> "crsr_ APIキー / トークン"
                                        p.authMode == ProviderAuthMode.NONE -> "認証不要（LAN URL 指定）"
                                        p.authMode == ProviderAuthMode.API_KEY -> "APIキーで接続"
                                        p.authMode == ProviderAuthMode.OAUTH_OR_JSON -> "OAuthトークン / 認証JSON"
                                        else -> "Access Token / Session Cookie"
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
            antigravityOAuth = antigravityOAuth,
            antigravityOAuthSettings = antigravityOAuthSettings,
            onSaveAntigravityOAuthSettings = onSaveAntigravityOAuthSettings,
            onBack = {
                if (provider == ProviderId.ANTIGRAVITY) antigravityOAuth?.cancel()
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
    antigravityOAuth: AntigravityOAuthCoordinator?,
    antigravityOAuthSettings: AntigravityOAuthClientSettings,
    onSaveAntigravityOAuthSettings: suspend (String, String) -> Unit,
    onBack: () -> Unit,
    onSave: suspend (ProviderId, String, MiniMaxRegion, String, AccountSecret, Boolean, CustomBalanceConfig?) -> Result<ConfiguredAccount>,
    onUpdate: suspend (String, String, MiniMaxRegion, String, AccountSecret?, CustomBalanceConfig?) -> Unit,
) {
    val colors = LocalOtuColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
    val initialCustom = editingAccount?.customBalance
    var customPreset by remember(editingAccount) { mutableStateOf(initialCustom?.preset ?: CustomBalancePreset.NEW_API) }
    var customUserId by remember(editingAccount) { mutableStateOf(initialCustom?.userId.orEmpty()) }
    var customPath by remember(editingAccount) { mutableStateOf(initialCustom?.path.orEmpty()) }
    var customAuthHeader by remember(editingAccount) { mutableStateOf(initialCustom?.authHeader ?: CustomBalanceAuthHeader.BEARER) }
    var customRemainingPath by remember(editingAccount) { mutableStateOf(initialCustom?.remainingPath.orEmpty()) }
    var customUsedPath by remember(editingAccount) { mutableStateOf(initialCustom?.usedPath.orEmpty()) }
    var customTotalPath by remember(editingAccount) { mutableStateOf(initialCustom?.totalPath.orEmpty()) }
    var customDivisor by remember(editingAccount) {
        mutableStateOf(
            initialCustom?.divisor?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() } ?: "1",
        )
    }
    var customCurrency by remember(editingAccount) { mutableStateOf(initialCustom?.currency?.ifBlank { "USD" } ?: "USD") }
    var detectedHint by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var manualCallbackInput by remember { mutableStateOf("") }
    var showManualInputs by remember(provider, editingAccount) {
        mutableStateOf(provider != ProviderId.ANTIGRAVITY || editingAccount != null)
    }

    val oauthState by if (provider == ProviderId.ANTIGRAVITY && antigravityOAuth != null) {
        antigravityOAuth.state.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<AntigravityOAuthUiState>(AntigravityOAuthUiState.Idle) }
    }

    if (provider == ProviderId.ANTIGRAVITY) {
        BackHandler {
            antigravityOAuth?.cancel()
            onBack()
        }
    }

    LaunchedEffect(oauthState) {
        if (provider != ProviderId.ANTIGRAVITY || antigravityOAuth == null) return@LaunchedEffect
        when (val st = oauthState) {
            is AntigravityOAuthUiState.Success -> {
                primaryInput = st.secret.accessToken
                refreshTokenInput = st.secret.refreshToken
                accountIdOrArn = st.secret.accountId
                val effectiveAlias = alias.trim().ifBlank { st.email.orEmpty() }
                if (alias.isBlank() && !st.email.isNullOrBlank()) {
                    alias = st.email
                }
                errorMsg = null
                isSubmitting = true
                try {
                    if (editingAccount == null) {
                        val res = onSave(provider, effectiveAlias, region, customHost.trim(), st.secret, true, null)
                        antigravityOAuth.reset()
                        if (res.isSuccess) {
                            onBack()
                        } else {
                            showManualInputs = true
                            errorMsg = res.exceptionOrNull()?.message ?: "接続テストに失敗しました"
                        }
                    } else {
                        onUpdate(editingAccount.id, effectiveAlias, region, customHost.trim(), st.secret, null)
                        antigravityOAuth.reset()
                        onBack()
                    }
                } finally {
                    isSubmitting = false
                }
            }
            is AntigravityOAuthUiState.Error -> {
                errorMsg = st.message
            }
            else -> Unit
        }
    }

    LaunchedEffect(primaryInput) {
        val trimmed = primaryInput.trim()
        if (trimmed.startsWith("{") ||
            trimmed.startsWith("1//") ||
            trimmed.contains("%3A%3A") ||
            (provider == ProviderId.DEVIN && trimmed.contains("="))
        ) {
            val parsed = runCatching {
                CredentialInputParser.parse(provider, trimmed, refreshTokenInput, accountIdOrArn, awsRegion)
            }.getOrNull()
            detectedHint = parsed?.detectedNote
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
            if (provider != ProviderId.ANTIGRAVITY && (provider.localFileHint.isNotBlank() || provider.keyHelpUrl.isNotBlank())) {
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
                placeholder = { Text(if (provider == ProviderId.ANTIGRAVITY) "未入力時は Google メールアドレス" else "例: 仕事用 / 個人") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (provider == ProviderId.ANTIGRAVITY && antigravityOAuth != null) {
                AntigravityGoogleLoginCard(
                    oauthState = oauthState,
                    clientSettings = antigravityOAuthSettings,
                    isEditing = editingAccount != null,
                    isSubmitting = isSubmitting,
                    manualCallbackInput = manualCallbackInput,
                    onManualCallbackChange = { manualCallbackInput = it },
                    onSaveClientSettings = onSaveAntigravityOAuthSettings,
                    onStartLogin = {
                        errorMsg = null
                        manualCallbackInput = ""
                        antigravityOAuth.startLogin(context)
                    },
                    onReopenBrowser = { antigravityOAuth.reopenBrowser(context) },
                    onCancelLogin = {
                        manualCallbackInput = ""
                        antigravityOAuth.cancel()
                    },
                    onSubmitManualCallback = {
                        errorMsg = null
                        antigravityOAuth.submitManualCallback(manualCallbackInput)
                    },
                    onPasteManualCallback = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) manualCallbackInput = pasted
                    },
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showManualInputs = !showManualInputs },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.card,
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "手動でトークン・JSON を入力",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(
                            imageVector = if (showManualInputs) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (provider == ProviderId.CUSTOM_BALANCE) {
                Text("プリセット", color = colors.textSecondary, fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CustomBalancePreset.entries.chunked(2).forEach { rowPresets ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowPresets.forEach { preset ->
                                FilterChip(
                                    selected = customPreset == preset,
                                    onClick = { customPreset = preset },
                                    label = { Text(preset.label, fontSize = 11.sp) },
                                )
                            }
                        }
                    }
                }
            }

            if (provider.supportsCustomHost) {
                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = {
                        Text(
                            when (provider) {
                                ProviderId.CUSTOM_BALANCE -> "ベース URL（例: https://api.example.com）"
                                ProviderId.OLLAMA -> "Ollama サーバー URL"
                                else -> "ホスト名"
                            },
                        )
                    },
                    placeholder = {
                        Text(
                            when (provider) {
                                ProviderId.CUSTOM_BALANCE -> "https://relay.example.com（末尾 /v1 は自動除去）"
                                ProviderId.OLLAMA -> "http://192.168.1.100:11434"
                                else -> "github.com"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (provider == ProviderId.CUSTOM_BALANCE && customPreset == CustomBalancePreset.NEW_API) {
                OutlinedTextField(
                    value = customUserId,
                    onValueChange = { customUserId = it },
                    label = { Text("New-Api-User（数値ユーザーID・任意）") },
                    placeholder = { Text("例: 1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (provider == ProviderId.CUSTOM_BALANCE && customPreset == CustomBalancePreset.CUSTOM) {
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text("エンドポイントパス") },
                    placeholder = { Text("例: /api/v1/balance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("認証ヘッダー", color = colors.textSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomBalanceAuthHeader.entries.forEach { header ->
                        FilterChip(
                            selected = customAuthHeader == header,
                            onClick = { customAuthHeader = header },
                            label = { Text(header.label, fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = customRemainingPath,
                    onValueChange = { customRemainingPath = it },
                    label = { Text("残高のドットパス（必須）") },
                    placeholder = { Text("例: data.balance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customUsedPath,
                    onValueChange = { customUsedPath = it },
                    label = { Text("使用量のドットパス（任意）") },
                    placeholder = { Text("例: data.used") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customTotalPath,
                    onValueChange = { customTotalPath = it },
                    label = { Text("合計枠のドットパス（任意）") },
                    placeholder = { Text("例: data.total") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customDivisor,
                        onValueChange = { customDivisor = it },
                        label = { Text("除数") },
                        placeholder = { Text("1") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customCurrency,
                        onValueChange = { customCurrency = it },
                        label = { Text("通貨") },
                        placeholder = { Text("USD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
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

            if (showManualInputs && provider.authMode != ProviderAuthMode.NONE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                provider == ProviderId.CURSOR || provider == ProviderId.GROK -> "API キー（crsr_...）/ トークン または 認証JSON"
                                provider.authMode == ProviderAuthMode.API_KEY -> "API キー"
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
                                if (provider == ProviderId.CURSOR || provider == ProviderId.GROK) {
                                    "crsr_... / Bearer Token / 認証JSON全体"
                                } else {
                                    "sk-... / Bearer Token / 認証JSON全体"
                                },
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

                if (provider == ProviderId.CODEX || provider == ProviderId.KIRO || provider == ProviderId.ANTIGRAVITY) {
                    OutlinedTextField(
                        value = accountIdOrArn,
                        onValueChange = { accountIdOrArn = it },
                        label = {
                            Text(
                                when (provider) {
                                    ProviderId.KIRO -> "profileArn（任意）"
                                    ProviderId.ANTIGRAVITY -> "Project ID（任意・自動取得可）"
                                    else -> "ChatGPT Account ID（任意）"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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

            if (showManualInputs) {
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
                            if (provider == ProviderId.OLLAMA) {
                                if (trimmedHost.isEmpty()) {
                                    errorMsg = "Ollama サーバー URL を入力してください（例: http://192.168.1.100:11434）"
                                    return@launch
                                }
                            }
                            if (provider == ProviderId.CUSTOM_BALANCE) {
                                if (!trimmedHost.startsWith("http://", ignoreCase = true) &&
                                    !trimmedHost.startsWith("https://", ignoreCase = true)
                                ) {
                                    errorMsg = "ベース URL を http:// または https:// から入力してください"
                                    return@launch
                                }
                                if (customPreset == CustomBalancePreset.CUSTOM && customRemainingPath.isBlank()) {
                                    errorMsg = "独自エンドポイントでは残高のドットパスを入力してください"
                                    return@launch
                                }
                            }
                            if (provider.authMode != ProviderAuthMode.NONE &&
                                primaryInput.isBlank() &&
                                refreshTokenInput.isBlank()
                            ) {
                                errorMsg = "APIキーまたは認証トークンを入力してください"
                                return@launch
                            }

                            val effectiveHost = if (provider == ProviderId.OLLAMA &&
                                !trimmedHost.startsWith("http://", ignoreCase = true) &&
                                !trimmedHost.startsWith("https://", ignoreCase = true)
                            ) {
                                "http://$trimmedHost"
                            } else {
                                trimmedHost
                            }

                            val customConfig = if (provider == ProviderId.CUSTOM_BALANCE) {
                                CustomBalanceConfig(
                                    preset = customPreset,
                                    userId = customUserId.trim(),
                                    path = customPath.trim(),
                                    authHeader = customAuthHeader,
                                    remainingPath = customRemainingPath.trim(),
                                    usedPath = customUsedPath.trim(),
                                    totalPath = customTotalPath.trim(),
                                    divisor = customDivisor.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
                                    currency = customCurrency.trim().uppercase().ifEmpty { "USD" },
                                )
                            } else {
                                null
                            }

                            isSubmitting = true
                            try {
                                val secretToSave = if (provider.authMode == ProviderAuthMode.NONE) {
                                    AccountSecret()
                                } else {
                                    CredentialInputParser.parse(
                                        providerId = provider,
                                        primaryInput = primaryInput,
                                        secondaryRefreshToken = refreshTokenInput,
                                        accountIdOrArn = accountIdOrArn,
                                        awsRegion = awsRegion,
                                    ).secret
                                }
                                if (editingAccount == null) {
                                    val res = onSave(provider, alias, region, effectiveHost, secretToSave, true, customConfig)
                                    if (res.isSuccess) {
                                        onBack()
                                    } else {
                                        errorMsg = res.exceptionOrNull()?.message ?: "接続テストに失敗しました"
                                    }
                                } else {
                                    onUpdate(editingAccount.id, alias, region, effectiveHost, secretToSave, customConfig)
                                    onBack()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorMsg = e.message?.takeIf { it.isNotBlank() } ?: "認証情報の解析に失敗しました"
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
}

@Composable
private fun AntigravityGoogleLoginCard(
    oauthState: AntigravityOAuthUiState,
    clientSettings: AntigravityOAuthClientSettings,
    isEditing: Boolean,
    isSubmitting: Boolean,
    manualCallbackInput: String,
    onManualCallbackChange: (String) -> Unit,
    onSaveClientSettings: suspend (String, String) -> Unit,
    onStartLogin: () -> Unit,
    onReopenBrowser: () -> Unit,
    onCancelLogin: () -> Unit,
    onSubmitManualCallback: () -> Unit,
    onPasteManualCallback: () -> Unit,
) {
    val colors = LocalOtuColors.current
    val scope = rememberCoroutineScope()
    var clientIdInput by remember(clientSettings.clientId) { mutableStateOf(clientSettings.clientId) }
    var clientSecretInput by remember(clientSettings.clientSecret) { mutableStateOf(clientSettings.clientSecret) }
    var isSavingClientSettings by remember { mutableStateOf(false) }
    var clientSettingsMessage by remember { mutableStateOf<String?>(null) }
    val isWaiting = oauthState is AntigravityOAuthUiState.WaitingForBrowser
    val isExchanging = oauthState is AntigravityOAuthUiState.ExchangingToken || isSubmitting
    val clientSettingsAreValid = clientIdInput.isNotBlank() && clientSecretInput.isNotBlank()
    val clientSettingsAreDirty = clientIdInput.trim() != clientSettings.clientId ||
        clientSecretInput.trim() != clientSettings.clientSecret

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Google OAuth クライアント設定",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Client ID と Client Secret はこの端末に暗号化して保存します。Google Cloud Console で作成した OAuth クライアントを入力してください。",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            OutlinedTextField(
                value = clientIdInput,
                onValueChange = {
                    clientIdInput = it
                    clientSettingsMessage = null
                },
                label = { Text("OAuth Client ID") },
                singleLine = true,
                enabled = !isWaiting && !isExchanging && !isSavingClientSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = clientSecretInput,
                onValueChange = {
                    clientSecretInput = it
                    clientSettingsMessage = null
                },
                label = { Text("OAuth Client Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isWaiting && !isExchanging && !isSavingClientSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        isSavingClientSettings = true
                        clientSettingsMessage = null
                        try {
                            onSaveClientSettings(clientIdInput, clientSecretInput)
                            clientSettingsMessage = "OAuth 設定をこの端末に保存しました。"
                        } catch (err: Exception) {
                            clientSettingsMessage = err.message ?: "OAuth 設定を保存できませんでした。"
                        } finally {
                            isSavingClientSettings = false
                        }
                    }
                },
                enabled = clientSettingsAreValid && clientSettingsAreDirty && !isSavingClientSettings && !isWaiting && !isExchanging,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.elevatedSurface),
            ) {
                Text(
                    text = if (isSavingClientSettings) "保存中..." else "OAuth設定を保存",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                )
            }
            if (clientSettingsMessage != null) {
                Text(clientSettingsMessage!!, color = colors.textSecondary, fontSize = 11.sp)
            }
            Text(
                text = "Google OAuth のリダイレクトURIは ${AntigravityOAuthConfig.REDIRECT_URI} を使用します。",
                color = colors.textMuted,
                fontSize = 10.sp,
            )

            Text(
                text = "Google アカウントでログイン",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val subtitle = when (oauthState) {
                is AntigravityOAuthUiState.WaitingForBrowser ->
                    if (oauthState.localServerActive) {
                        "ブラウザでログインを完了するとアプリに自動で戻ります。"
                    } else {
                        "待機ポートが使用中のため、ログイン後の URL を下に貼り付けてください。"
                    }
                is AntigravityOAuthUiState.ExchangingToken ->
                    "トークンとプロジェクト情報を取得しています..."
                else ->
                    "ブラウザで Google にログインし、トークンとプロジェクト ID を自動取得します。"
            }
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)

            when {
                isExchanging -> {
                    Button(
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.textPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("認証を完了中...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                isWaiting -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onReopenBrowser,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.border),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("ブラウザを再度開く", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onCancelLogin,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.border),
                        ) {
                            Text("キャンセル", fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = "自動で戻らない場合はリダイレクト後の URL を貼り付け",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                    )
                    OutlinedTextField(
                        value = manualCallbackInput,
                        onValueChange = onManualCallbackChange,
                        placeholder = { Text("http://localhost:51121/oauth-callback?code=...", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onPasteManualCallback,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.border),
                        ) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("貼り付け", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onSubmitManualCallback,
                            enabled = manualCallbackInput.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("URL から認証を完了", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = onStartLogin,
                        enabled = clientSettings.isConfigured && !clientSettingsAreDirty,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                !clientSettings.isConfigured -> "先に OAuth Client ID / Secret を設定"
                                clientSettingsAreDirty -> "OAuth設定を保存してください"
                                isEditing -> "Google で再ログイン"
                                else -> "Google でログイン"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
