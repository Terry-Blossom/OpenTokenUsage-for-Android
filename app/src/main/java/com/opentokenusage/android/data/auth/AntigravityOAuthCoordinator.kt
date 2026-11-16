package com.opentokenusage.android.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.opentokenusage.android.domain.model.AccountSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AntigravityOAuthUiState {
    data object Idle : AntigravityOAuthUiState
    data class WaitingForBrowser(
        val authUrl: String,
        val state: String,
        val codeVerifier: String,
        val localServerActive: Boolean,
    ) : AntigravityOAuthUiState
    data object ExchangingToken : AntigravityOAuthUiState
    data class Success(
        val secret: AccountSecret,
        val email: String?,
        val projectId: String?,
    ) : AntigravityOAuthUiState
    data class Error(
        val message: String,
    ) : AntigravityOAuthUiState
}

/**
 * Coordinates the Antigravity Google OAuth PKCE flow across browser handoff, local loopback
 * callback capture, manual URL fallback, and token exchange.
 */
class AntigravityOAuthCoordinator(
    private val appContext: Context,
    private val oauthClient: AntigravityOAuthClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<AntigravityOAuthUiState>(AntigravityOAuthUiState.Idle)
    val state: StateFlow<AntigravityOAuthUiState> = _state.asStateFlow()

    private var activeServer: AntigravityOAuthCallbackServer? = null
    private var waitJob: Job? = null
    private var currentRequest: AntigravityAuthorizationRequest? = null

    fun startLogin(activityContext: Context) {
        stopActiveWait()

        val request = runCatching { oauthClient.buildAuthorizationRequest() }.getOrElse { err ->
            _state.value = AntigravityOAuthUiState.Error(
                err.message ?: "Google OAuth クライアント設定が未構成です。",
            )
            return
        }
        currentRequest = request

        val server = AntigravityOAuthCallbackServer()
        val serverStarted = server.start(scope)
        if (serverStarted) {
            activeServer = server
            AntigravityOAuthService.start(appContext)
        }

        _state.value = AntigravityOAuthUiState.WaitingForBrowser(
            authUrl = request.url,
            state = request.state,
            codeVerifier = request.codeVerifier,
            localServerActive = serverStarted,
        )

        openBrowser(activityContext, request.url)

        if (serverStarted) {
            waitJob = scope.launch {
                val callback = server.awaitCallback(timeoutMs = 5 * 60_000L)
                if (callback == null) {
                    // Do not clobber manual completion or explicit cancellation.
                    if (_state.value is AntigravityOAuthUiState.WaitingForBrowser) {
                        stopActiveWait(cancelWaitJob = false)
                        _state.value = AntigravityOAuthUiState.Error("Google ログインがタイムアウトしました。再度お試しください。")
                    }
                    return@launch
                }
                completeWithCallback(callback, request, cancelWaitJob = false)
            }
        }
    }

    fun reopenBrowser(activityContext: Context) {
        val req = currentRequest ?: return
        openBrowser(activityContext, req.url)
    }

    fun submitManualCallback(rawInput: String) {
        val req = currentRequest
        if (req == null) {
            _state.value = AntigravityOAuthUiState.Error("ログインセッションが見つかりません。最初からやり直してください。")
            return
        }
        val parsed = AntigravityPkce.parseCallbackInput(rawInput)
        if (parsed.code.isNullOrBlank() && parsed.error.isNullOrBlank()) {
            _state.value = AntigravityOAuthUiState.Error("リダイレクトURLまたは認証コードを確認してください。")
            return
        }
        scope.launch {
            completeWithCallback(parsed, req)
        }
    }

    fun cancel() {
        stopActiveWait()
        currentRequest = null
        _state.value = AntigravityOAuthUiState.Idle
    }

    fun reset() {
        stopActiveWait()
        currentRequest = null
        _state.value = AntigravityOAuthUiState.Idle
    }

    private suspend fun completeWithCallback(
        callback: AntigravityCallbackParams,
        request: AntigravityAuthorizationRequest,
        cancelWaitJob: Boolean = true,
    ) {
        stopActiveWait(cancelWaitJob)

        if (!callback.error.isNullOrBlank()) {
            val detail = callback.errorDescription?.takeIf { it.isNotBlank() } ?: callback.error
            _state.value = AntigravityOAuthUiState.Error("Google ログインが中断されました ($detail)")
            return
        }

        val code = callback.code?.trim().orEmpty()
        if (code.isEmpty()) {
            _state.value = AntigravityOAuthUiState.Error("認証コードを取得できませんでした。")
            return
        }

        if (!callback.state.isNullOrBlank() && callback.state != request.state) {
            _state.value = AntigravityOAuthUiState.Error("OAuth state が一致しません。再度ログインしてください。")
            return
        }

        _state.value = AntigravityOAuthUiState.ExchangingToken
        val result = runCatching {
            oauthClient.exchangeCodeForTokens(
                code = code,
                codeVerifier = request.codeVerifier,
            )
        }
        result.fold(
            onSuccess = { auth ->
                currentRequest = null
                _state.value = AntigravityOAuthUiState.Success(
                    secret = auth.secret,
                    email = auth.email,
                    projectId = auth.projectId,
                )
            },
            onFailure = { err ->
                _state.value = AntigravityOAuthUiState.Error(
                    err.message ?: "Google トークン取得に失敗しました。",
                )
            },
        )
    }

    private fun stopActiveWait(cancelWaitJob: Boolean = true) {
        if (cancelWaitJob) waitJob?.cancel()
        waitJob = null
        activeServer?.stop()
        activeServer = null
        AntigravityOAuthService.stop(appContext)
    }

    private fun openBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // Hint Custom Tabs support when available without requiring androidx.browser dependency.
            putExtra("android.support.customtabs.extra.SESSION", null as Bundle?)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }.onFailure { err ->
            stopActiveWait()
            _state.value = AntigravityOAuthUiState.Error(
                "ブラウザを開けませんでした: ${err.message ?: "対応ブラウザが見つかりません"}",
            )
        }
    }
}
