package com.opentokenusage.android.data.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * Lightweight loopback HTTP listener for Google OAuth redirects on `http://localhost:51121/oauth-callback`.
 */
class AntigravityOAuthCallbackServer(
    private val port: Int = AntigravityOAuthConfig.REDIRECT_PORT,
    private val callbackPath: String = AntigravityOAuthConfig.REDIRECT_PATH,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val callbackDeferred = CompletableDeferred<AntigravityCallbackParams>()

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun start(scope: CoroutineScope): Boolean {
        if (serverSocket != null) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(AntigravityOAuthConfig.REDIRECT_HOST), port))
            }
        }.getOrNull() ?: return false

        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (!socket.isClosed && !callbackDeferred.isCompleted) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    handleClient(client)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
        return true
    }

    suspend fun awaitCallback(timeoutMs: Long = 5 * 60_000L): AntigravityCallbackParams? =
        withTimeoutOrNull(timeoutMs) { callbackDeferred.await() }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (!callbackDeferred.isCompleted) {
            callbackDeferred.cancel()
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                // Drain request headers before writing response so the browser does not see RST.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1) ?: "/"
                val uri = runCatching { URI(target) }.getOrNull()
                val path = uri?.path ?: target.substringBefore('?')

                if (path != callbackPath) {
                    writeResponse(sock, status = "404 Not Found", contentType = "text/plain; charset=utf-8", body = "Not found")
                    return
                }

                val query = AntigravityPkce.parseQueryString(uri?.rawQuery ?: target.substringAfter('?', ""))
                val params = AntigravityCallbackParams(
                    code = query["code"],
                    state = query["state"],
                    error = query["error"],
                    errorDescription = query["error_description"],
                )

                val html = buildResultHtml(isSuccess = params.error.isNullOrBlank() && !params.code.isNullOrBlank())
                writeResponse(sock, status = "200 OK", contentType = "text/html; charset=utf-8", body = html)
                callbackDeferred.complete(params)
                runCatching { serverSocket?.close() }
            }
        }
    }

    private fun writeResponse(sock: Socket, status: String, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = sock.getOutputStream()
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    private fun buildResultHtml(isSuccess: Boolean): String {
        val title = if (isSuccess) "認証完了" else "認証を中断しました"
        val heading = if (isSuccess) "ログイン完了" else "ログインが完了しませんでした"
        val message = if (isSuccess) {
            "Antigravity の認証情報を取得しました。OpenTokenUsage に戻って確認してください。"
        } else {
            "Google ログインがキャンセルされたかエラーが発生しました。アプリに戻ってやり直してください。"
        }
        val accent = if (isSuccess) "#10B981" else "#EF4444"
        val iconSvg = if (isSuccess) {
            """<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7" />"""
        } else {
            """<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />"""
        }
        val autoRedirectScript = if (isSuccess) {
            """
            <script>
              setTimeout(function() {
                window.location.href = "${AntigravityOAuthConfig.APP_RETURN_URI}";
              }, 150);
            </script>
            """.trimIndent()
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>$title</title>
                <style>
                  :root {
                    --bg: #F3F3F3;
                    --card: #FFFFFF;
                    --text: #1A1A1A;
                    --muted: #5D5D5D;
                    --border: #E5E7EB;
                  }
                  @media (prefers-color-scheme: dark) {
                    :root {
                      --bg: #000000;
                      --card: #0A0A0A;
                      --text: #FFFFFF;
                      --muted: #A1A1AA;
                      --border: #27272A;
                    }
                  }
                  body {
                    margin: 0;
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-family: system-ui, -apple-system, sans-serif;
                    background: var(--bg);
                    color: var(--text);
                    padding: 20px;
                  }
                  .card {
                    background: var(--card);
                    border: 1px solid var(--border);
                    border-radius: 12px;
                    padding: 28px 22px;
                    width: 100%;
                    max-width: 360px;
                    text-align: center;
                  }
                  .badge {
                    width: 52px;
                    height: 52px;
                    border-radius: 50%;
                    background: ${accent}1F;
                    color: $accent;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin: 0 auto 16px;
                  }
                  h1 { font-size: 18px; margin: 0 0 8px; }
                  p { font-size: 13px; line-height: 1.5; color: var(--muted); margin: 0 0 20px; }
                  .btn {
                    display: block;
                    width: 100%;
                    box-sizing: border-box;
                    padding: 12px 16px;
                    border-radius: 8px;
                    background: #0078D4;
                    color: #FFFFFF;
                    text-decoration: none;
                    font-size: 14px;
                    font-weight: 600;
                  }
                </style>
              </head>
              <body>
                <div class="card">
                  <div class="badge">
                    <svg width="26" height="26" fill="none" viewBox="0 0 24 24" stroke="currentColor">$iconSvg</svg>
                  </div>
                  <h1>$heading</h1>
                  <p>$message</p>
                  <a class="btn" href="${AntigravityOAuthConfig.APP_RETURN_URI}">OpenTokenUsage に戻る</a>
                </div>
                $autoRedirectScript
              </body>
            </html>
        """.trimIndent()
    }
}
