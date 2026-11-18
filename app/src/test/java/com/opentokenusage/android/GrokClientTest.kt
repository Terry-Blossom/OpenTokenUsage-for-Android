package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.clients.GrokClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricFormat
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GrokClientTest {
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

    private fun client() = GrokClient(
        http,
        ProviderEndpointOverrides(
            mapOf(
                "cursor" to baseUrl,
                "grok" to baseUrl,
                "grok-oauth" to baseUrl,
            ),
        ),
    )

    @Test
    fun exchangesCursorApiKeyAndReadsGrokBotUsage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"cursor-session","refreshToken":"cursor-refresh"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usagePercent":41.5,"hasNonZeroIncludedLimit":true,"nextResetTimestampUtc":"2026-11-20T00:00:00Z"}""",
            ),
        )

        val result = client().probe(
            ConfiguredAccount("g1", ProviderId.GROK.id),
            AccountSecret(apiKey = "crsr_test_key"),
        )

        val exchangeReq = server.takeRequest()
        assertEquals("/auth/exchange_user_api_key", exchangeReq.path)
        assertEquals("Bearer crsr_test_key", exchangeReq.getHeader("Authorization"))
        assertTrue(exchangeReq.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("{}", exchangeReq.body.readUtf8())

        val usageReq = server.takeRequest()
        assertEquals("/aiserver.v1.DashboardService/GetSandUsageStatus", usageReq.path)
        assertEquals("Bearer cursor-session", usageReq.getHeader("Authorization"))
        assertEquals("1", usageReq.getHeader("Connect-Protocol-Version"))
        assertEquals("{}", usageReq.body.readUtf8())

        assertEquals("Grok Bot", result.plan)
        assertEquals("crsr_test_key", result.updatedSecret?.apiKey)
        assertEquals("cursor-session", result.updatedSecret?.accessToken)
        val weekly = result.lines.single() as MetricLine.Progress
        assertEquals("Grok Bot", weekly.label)
        assertEquals(41.5, weekly.used, 0.01)
        assertEquals(MetricFormat.Percent, weekly.format)
        assertEquals("weekly", weekly.period)
    }

    @Test
    fun exchange401ReportsCursorApiKeyAuthFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            client().probe(
                ConfiguredAccount("g1", ProviderId.GROK.id),
                AccountSecret(apiKey = "crsr_test_key"),
            )
            fail("Expected ProviderProbeException on 401 from exchange endpoint")
        } catch (e: ProviderProbeException) {
            assertTrue(e.isAuthError)
            assertEquals(401, e.statusCode)
            assertTrue(e.message.contains("Cursor API キー"))
            assertTrue(e.message.contains("crsr_"))
        }
    }

    @Test
    fun usageRpc403AfterExchangeIsNotReportedAsApiKeyAuthFailure() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"cursor-session","refreshToken":"cursor-refresh"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            client().probe(
                ConfiguredAccount("g1", ProviderId.GROK.id),
                AccountSecret(apiKey = "crsr_test_key"),
            )
            fail("Expected ProviderProbeException on 403 from GetSandUsageStatus")
        } catch (e: ProviderProbeException) {
            assertFalse(e.isAuthError)
            assertEquals(403, e.statusCode)
            assertTrue(e.message.contains("Cursor Grok Bot"))
            assertTrue(e.message.contains("共有"))
            assertFalse(e.message.contains("API キー"))
            assertFalse(e.message.contains("認証に失敗"))
        }
    }

    @Test
    fun usageRpcWithoutSeparateAllowanceIsNotReportedAsAuthFailure() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"cursor-session","refreshToken":"cursor-refresh"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usagePercent":0,"hasNonZeroIncludedLimit":false}""",
            ),
        )

        try {
            client().probe(
                ConfiguredAccount("g1", ProviderId.GROK.id),
                AccountSecret(apiKey = "crsr_test_key"),
            )
            fail("Expected ProviderProbeException when hasNonZeroIncludedLimit is false")
        } catch (e: ProviderProbeException) {
            assertFalse(e.isAuthError)
            assertTrue(e.message.contains("Cursor Grok Bot"))
            assertTrue(e.message.contains("独立"))
            assertFalse(e.message.contains("認証に失敗"))
        }
    }

    @Test
    fun usageRpcMissingUsagePercentIsNotReportedAsAuthFailure() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"cursor-session","refreshToken":"cursor-refresh"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        try {
            client().probe(
                ConfiguredAccount("g1", ProviderId.GROK.id),
                AccountSecret(apiKey = "crsr_test_key"),
            )
            fail("Expected ProviderProbeException when usagePercent is missing")
        } catch (e: ProviderProbeException) {
            assertFalse(e.isAuthError)
            assertTrue(e.message.contains("Cursor Grok Bot"))
            assertFalse(e.message.contains("認証に失敗"))
        }
    }

    @Test
    fun networkIoErrorIsNotReportedAsAuthFailure() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            client().probe(
                ConfiguredAccount("g1", ProviderId.GROK.id),
                AccountSecret(apiKey = "crsr_test_key"),
            )
            fail("Expected ProviderProbeException on IO failure")
        } catch (e: ProviderProbeException) {
            assertFalse(e.isAuthError)
            assertTrue(e.message.contains("通信エラー"))
            assertFalse(e.message.contains("認証に失敗"))
        }
    }

    @Test
    fun staleRefreshTokenPrefersReExchangingCrsrApiKeyWhenExpired() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"fresh-cursor-session","refreshToken":"fresh-cursor-refresh"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usagePercent":19.0,"hasNonZeroIncludedLimit":true}""",
            ),
        )

        val result = client().probe(
            ConfiguredAccount("g1", ProviderId.GROK.id),
            AccountSecret(
                apiKey = "crsr_test_key",
                accessToken = "expired-session",
                refreshToken = "stale-refresh-jwt",
                expiresAtMs = 1_000L,
            ),
            nowMs = 2_000_000L,
        )

        val exchangeReq = server.takeRequest()
        assertEquals("/auth/exchange_user_api_key", exchangeReq.path)
        assertEquals("Bearer crsr_test_key", exchangeReq.getHeader("Authorization"))
        assertEquals("crsr_test_key", result.updatedSecret?.apiKey)
        assertEquals("fresh-cursor-session", result.updatedSecret?.accessToken)
        assertEquals(19.0, (result.lines.single() as MetricLine.Progress).used, 0.01)
    }

    @Test
    fun staleAccessAndRefreshTokensFallBackToReExchangingCrsrApiKeyOn401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"fresh-cursor-session","refreshToken":"fresh-cursor-refresh"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usagePercent":27.5,"hasNonZeroIncludedLimit":true}""",
            ),
        )

        val result = client().probe(
            ConfiguredAccount("g1", ProviderId.GROK.id),
            AccountSecret(
                apiKey = "crsr_test_key",
                accessToken = "stale-session",
                refreshToken = "stale-refresh-jwt",
            ),
        )

        assertEquals("/aiserver.v1.DashboardService/GetSandUsageStatus", server.takeRequest().path)
        val exchangeReq = server.takeRequest()
        assertEquals("/auth/exchange_user_api_key", exchangeReq.path)
        assertEquals("Bearer crsr_test_key", exchangeReq.getHeader("Authorization"))
        assertEquals("/aiserver.v1.DashboardService/GetSandUsageStatus", server.takeRequest().path)
        assertEquals("crsr_test_key", result.updatedSecret?.apiKey)
        assertEquals(27.5, (result.lines.single() as MetricLine.Progress).used, 0.01)
    }

    @Test
    fun nativeGrokAuthFailureNamesGrokXai() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            client().probe(
                ConfiguredAccount("g2", ProviderId.GROK.id),
                AccountSecret(accessToken = "xai-expired-token"),
            )
            fail("Expected ProviderProbeException on 401 from native Grok")
        } catch (e: ProviderProbeException) {
            assertTrue(e.isAuthError)
            assertEquals(401, e.statusCode)
            assertTrue(e.message.contains("Grok (xAI)"))
            assertFalse(e.message.contains("Cursor"))
        }
    }
}
