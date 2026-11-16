package com.opentokenusage.android

import com.opentokenusage.android.data.auth.AntigravityOAuthCallbackServer
import com.opentokenusage.android.data.auth.AntigravityOAuthClient
import com.opentokenusage.android.data.auth.AntigravityOAuthConfig
import com.opentokenusage.android.data.auth.AntigravityPkce
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI

class AntigravityOAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var baseUrl: String

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun buildAuthorizationRequestIncludesRequiredGoogleParamsAndPkce() {
        val req = AntigravityPkce.buildAuthorizationRequest(clientId = "test-client-id")
        val uri = URI(req.url)
        assertEquals("accounts.google.com", uri.host)
        assertEquals("/o/oauth2/v2/auth", uri.path)

        val query = AntigravityPkce.parseQueryString(uri.rawQuery)
        assertEquals("test-client-id", query["client_id"])
        assertEquals("code", query["response_type"])
        assertEquals(AntigravityOAuthConfig.REDIRECT_URI, query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("offline", query["access_type"])
        assertEquals(req.state, query["state"])
        assertTrue(query["scope"]!!.contains("https://www.googleapis.com/auth/cloud-platform"))
        assertTrue(req.codeVerifier.length >= 43)
        assertTrue(query["code_challenge"]!!.isNotBlank())
    }

    @Test
    fun parseCallbackInputHandlesFullUrlQueryAndRawCode() {
        val full = AntigravityPkce.parseCallbackInput(
            "http://localhost:51121/oauth-callback?state=st_123&code=4%2F0AbC_xyz&scope=email",
        )
        assertEquals("4/0AbC_xyz", full.code)
        assertEquals("st_123", full.state)
        assertNull(full.error)

        val err = AntigravityPkce.parseCallbackInput(
            "http://localhost:51121/oauth-callback?error=access_denied&state=st_123",
        )
        assertEquals("access_denied", err.error)
        assertEquals("st_123", err.state)

        val raw = AntigravityPkce.parseCallbackInput("4/0RawCodeOnly")
        assertEquals("4/0RawCodeOnly", raw.code)
        assertNull(raw.state)
    }

    @Test
    fun exchangeCodeForTokensResolvesEmailAndProjectFromLoadCodeAssist() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"ya29.access","refresh_token":"1//0gRefresh","expires_in":3600}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"email":"dev@example.com"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"cloudaicompanionProject":{"id":"managed-proj-42"}}""",
            ),
        )

        val client = AntigravityOAuthClient(
            http = http,
            overrides = ProviderEndpointOverrides(
                mapOf(
                    "google-oauth" to baseUrl,
                    "google-userinfo" to baseUrl,
                    "antigravity-daily" to baseUrl,
                ),
            ),
            clientIdProvider = { "test-client-id" },
            clientSecretProvider = { "test-client-secret" },
        )

        val res = client.exchangeCodeForTokens(
            code = "auth-code-1",
            codeVerifier = "verifier-1",
            nowMs = 1_700_000_000_000L,
        )

        assertEquals("ya29.access", res.secret.accessToken)
        assertEquals("1//0gRefresh", res.secret.refreshToken)
        assertEquals(1_700_003_600_000L, res.secret.expiresAtMs)
        assertEquals("managed-proj-42", res.secret.accountId)
        assertEquals("dev@example.com", res.email)

        val tokenReq = server.takeRequest()
        assertEquals("/token", tokenReq.path)
        val tokenBody = tokenReq.body.readUtf8()
        assertTrue(tokenBody.contains("grant_type=authorization_code"))
        assertTrue(tokenBody.contains("code_verifier=verifier-1"))

        val userInfoReq = server.takeRequest()
        assertEquals("/oauth2/v1/userinfo?alt=json", userInfoReq.path)

        val loadReq = server.takeRequest()
        assertEquals("/v1internal:loadCodeAssist", loadReq.path)
        assertTrue(loadReq.body.readUtf8().contains("ANTIGRAVITY"))
    }

    @Test
    fun exchangeCodeForTokensFallsBackToOnboardUserWhenProjectMissing() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"ya29.acc2","refresh_token":"1//0gRef2","expires_in":3600}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"new@example.com"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"allowedTiers":[{"id":"free-tier","isDefault":true}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"done":true,"response":{"cloudaicompanionProject":"onboarded-proj-99"}}""",
            ),
        )

        val client = AntigravityOAuthClient(
            http = http,
            overrides = ProviderEndpointOverrides(
                mapOf(
                    "google-oauth" to baseUrl,
                    "google-userinfo" to baseUrl,
                    "antigravity-daily" to baseUrl,
                ),
            ),
            clientIdProvider = { "test-client-id" },
            clientSecretProvider = { "test-client-secret" },
        )

        val res = client.exchangeCodeForTokens("code-2", "ver-2")
        assertEquals("onboarded-proj-99", res.projectId)
        assertEquals("onboarded-proj-99", res.secret.accountId)
    }

    @Test
    fun callbackServerIgnoresFaviconAndCapturesOAuthCallback() = runTest {
        val callbackServer = AntigravityOAuthCallbackServer(port = 0)
        assertTrue(callbackServer.start(this))
        try {
            val port = callbackServer.boundPort
            assertTrue(port > 0)

            val faviconResp = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/favicon.ico").build(),
            ).execute()
            assertEquals(404, faviconResp.code)
            faviconResp.close()

            val callbackResp = http.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/oauth-callback?code=4%2F0TestCode&state=st_abc")
                    .build(),
            ).execute()
            assertEquals(200, callbackResp.code)
            val html = callbackResp.body?.string().orEmpty()
            assertTrue(html.contains(AntigravityOAuthConfig.APP_RETURN_URI))
            callbackResp.close()

            val captured = callbackServer.awaitCallback(timeoutMs = 2000L)
            assertNotNull(captured)
            assertEquals("4/0TestCode", captured!!.code)
            assertEquals("st_abc", captured.state)
        } finally {
            callbackServer.stop()
        }
    }
}
