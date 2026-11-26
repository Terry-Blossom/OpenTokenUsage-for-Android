package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.clients.CodebuffClient
import com.opentokenusage.android.data.network.clients.KiloClient
import com.opentokenusage.android.data.network.clients.KimiApiClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.domain.logic.CredentialInputParser
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AdditionalProviderClientsTest {
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
    fun kimiApiParsesGlobalBalanceWithVouchersAndCash() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"available_balance":49.58,"voucher_balance":46.58,"cash_balance":3.0}}""",
            ),
        )
        val client = KimiApiClient(
            http,
            ProviderEndpointOverrides(mapOf("kimi-api-global" to baseUrl, "kimi-api-cn" to baseUrl)),
        )
        val res = client.probe(
            ConfiguredAccount("ka1", ProviderId.KIMI_API.id),
            AccountSecret(apiKey = "sk-moonshot"),
        )
        val req = server.takeRequest()
        assertEquals("/v1/users/me/balance", req.path)
        assertEquals("Bearer sk-moonshot", req.getHeader("Authorization"))
        assertEquals("Global", res.plan)
        assertEquals("$49.58", (res.lines[0] as MetricLine.Text).value)
        assertEquals("$46.58", (res.lines[1] as MetricLine.Text).value)
        assertEquals("detail", res.lines[1].scope)
        assertEquals("$3.00", (res.lines[2] as MetricLine.Text).value)
    }

    @Test
    fun kimiApiFallsBackToCnOnGlobal401AndMarksZeroRed() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"available_balance":0,"voucher_balance":0,"cash_balance":0}}""",
            ),
        )
        val client = KimiApiClient(
            http,
            ProviderEndpointOverrides(mapOf("kimi-api-global" to baseUrl, "kimi-api-cn" to baseUrl)),
        )
        val res = client.probe(
            ConfiguredAccount("ka2", ProviderId.KIMI_API.id),
            AccountSecret(apiKey = "sk-cn-key"),
        )
        assertEquals("CN", res.plan)
        val balance = res.lines[0] as MetricLine.Text
        assertEquals("¥0.00", balance.value)
        assertEquals("#EF4444", balance.colorHex)
    }

    @Test
    fun kimiApiReportsAuthErrorOnlyWhenAllPlatformsRefuse() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        val client = KimiApiClient(
            http,
            ProviderEndpointOverrides(mapOf("kimi-api-global" to baseUrl, "kimi-api-cn" to baseUrl)),
        )
        try {
            client.probe(ConfiguredAccount("ka3", ProviderId.KIMI_API.id), AccountSecret(apiKey = "bad"))
            fail("Expected auth error")
        } catch (e: ProviderProbeException) {
            assertTrue(e.isAuthError)
        }

        // One 401 and one 503 should NOT be classified as invalid API key
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            client.probe(ConfiguredAccount("ka4", ProviderId.KIMI_API.id), AccountSecret(apiKey = "maybe-cn"))
            fail("Expected server error")
        } catch (e: ProviderProbeException) {
            assertFalse(e.isAuthError)
            assertEquals(503, e.statusCode)
        }
    }

    @Test
    fun kiloParsesCreditBlocksAndActivePassAndIgnoresCancelledPass() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {"result":{"data":{"json":{"creditBlocks":[{"amount_mUsd":20000000,"balance_mUsd":12500000}]}}}},
                  {"result":{"data":{"json":{"subscription":{"status":"active","tier":"tier_49","currentPeriodUsageUsd":14.5,"currentPeriodBaseCreditsUsd":40,"currentPeriodBonusCreditsUsd":9,"nextBillingAt":"2026-12-01T00:00:00Z"}}}}}
                ]
                """.trimIndent(),
            ),
        )
        val client = KiloClient(http, ProviderEndpointOverrides(mapOf("kilo" to baseUrl)))
        val res = client.probe(
            ConfiguredAccount("kilo1", ProviderId.KILO.id),
            AccountSecret(apiKey = "kilo-key"),
        )
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/trpc/user.getCreditBlocks,kiloPass.getState?batch=1"))
        assertEquals("Bearer kilo-key", req.getHeader("Authorization"))
        assertEquals("Pro", res.plan)
        val credits = res.lines[0] as MetricLine.Progress
        assertEquals("Credits", credits.label)
        assertEquals(7.5, credits.used, 0.01)
        assertEquals(20.0, credits.limit, 0.01)
        val pass = res.lines[1] as MetricLine.Progress
        assertEquals("Kilo Pass", pass.label)
        assertEquals(14.5, pass.used, 0.01)
        assertEquals(49.0, pass.limit, 0.01)
        assertEquals("monthly", pass.period)
        assertNotNull(pass.resetsAtMs)

        // Fresh account with cancelled Pass shows $0.00 Credits text and no Pass line
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {"result":{"data":{"json":{"creditBlocks":[],"totalBalance_mUsd":0}}}},
                  {"result":{"data":{"json":{"subscription":{"status":"canceled","tier":"tier_19","currentPeriodUsageUsd":10,"currentPeriodBaseCreditsUsd":19}}}}}
                ]
                """.trimIndent(),
            ),
        )
        val fresh = client.probe(ConfiguredAccount("kilo2", ProviderId.KILO.id), AccountSecret(apiKey = "kilo-key"))
        assertEquals(null, fresh.plan)
        assertEquals(1, fresh.lines.size)
        assertEquals("$0.00", (fresh.lines[0] as MetricLine.Text).value)

        // Kilo auth.json parsing
        val parsed = CredentialInputParser.parse(ProviderId.KILO, """{"kilo":{"access":"tok-from-cli"}}""")
        assertEquals("tok-from-cli", parsed.secret.apiKey)
    }

    @Test
    fun codebuffParsesUsageAndSubscriptionAndToleratesSubscriptionFailure() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usage":350,"remainingBalance":650,"next_quota_reset":"2026-12-01T00:00:00Z"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"subscription":{"displayName":"Pro Plan"},"rateLimit":{"weeklyUsed":45,"weeklyLimit":100,"weeklyResetsAt":"2026-11-30T00:00:00Z"}}""",
            ),
        )

        val client = CodebuffClient(http, ProviderEndpointOverrides(mapOf("codebuff" to baseUrl)))
        val res = client.probe(
            ConfiguredAccount("cbuf1", ProviderId.CODEBUFF.id),
            AccountSecret(apiKey = "cbuf-key"),
        )
        val usageReq = server.takeRequest()
        assertEquals("POST", usageReq.method)
        assertEquals("/api/v1/usage", usageReq.path)
        assertEquals("Bearer cbuf-key", usageReq.getHeader("Authorization"))
        assertTrue(usageReq.body.readUtf8().contains("opentokenusage"))
        assertEquals("/api/user/subscription", server.takeRequest().path)

        assertEquals("Pro Plan", res.plan)
        val credits = res.lines[0] as MetricLine.Progress
        assertEquals("Credits", credits.label)
        assertEquals(350.0, credits.used, 0.01)
        assertEquals(1000.0, credits.limit, 0.01)
        val weekly = res.lines[1] as MetricLine.Progress
        assertEquals("Weekly", weekly.label)
        assertEquals(45.0, weekly.used, 0.01)
        assertEquals("weekly", weekly.period)

        // Subscription failure tolerated, remaining-only fallback works
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"remainingBalance":420}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        val fallback = client.probe(
            ConfiguredAccount("cbuf2", ProviderId.CODEBUFF.id),
            AccountSecret(apiKey = "cbuf-key"),
        )
        assertEquals(1, fallback.lines.size)
        assertEquals("420 credits", (fallback.lines[0] as MetricLine.Text).value)

        // Codebuff credentials.json parsing
        val parsed = CredentialInputParser.parse(
            ProviderId.CODEBUFF,
            """{"default":{"authToken":"codebuff-cli-token"}}""",
        )
        assertEquals("codebuff-cli-token", parsed.secret.apiKey)
    }
}
