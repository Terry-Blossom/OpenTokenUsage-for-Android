package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.clients.ClaudeClient
import com.opentokenusage.android.data.network.clients.CodexClient
import com.opentokenusage.android.data.network.clients.CopilotClient
import com.opentokenusage.android.data.network.clients.OpenRouterClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.data.network.clients.ZaiClient
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProviderClientsMockTest {
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
    fun openRouterParsesLimitAndCredits() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"limit":50.0,"limit_remaining":35.5,"limit_reset":"monthly","usage_daily":2.1,"usage_monthly":14.5,"is_free_tier":false}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"total_credits":100.0,"total_usage":22.0}}""",
            ),
        )
        val client = OpenRouterClient(http, ProviderEndpointOverrides(mapOf("openrouter" to baseUrl)))
        val res = client.probe(
            ConfiguredAccount("1", ProviderId.OPENROUTER.id),
            AccountSecret(apiKey = "sk-or-test"),
        )
        assertEquals("Paid", res.plan)
        val first = res.lines.first() as MetricLine.Progress
        assertEquals(14.5, first.used, 0.01)
        assertEquals(50.0, first.limit, 0.01)
    }

    @Test
    fun claudeRotatesSingleUseRefreshTokenOn401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"new-access","refresh_token":"rotated-refresh","expires_in":3600}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"five_hour":{"utilization":28.0,"resets_at":"2026-11-15T10:00:00Z"},"seven_day":{"utilization":42.0,"resets_at":"2026-11-20T00:00:00Z"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"organization":{"plan_display_name":"Claude Max"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"cedar_ember":null}"""),
        )
        val client = ClaudeClient(
            http,
            ProviderEndpointOverrides(mapOf("claude" to baseUrl, "claude-oauth" to baseUrl)),
        )
        val res = client.probe(
            ConfiguredAccount("c1", ProviderId.CLAUDE.id),
            AccountSecret(accessToken = "old-access", refreshToken = "old-refresh"),
            nowMs = 1_794_720_000_000L,
        )
        assertEquals("Claude Max", res.plan)
        assertEquals("new-access", res.updatedSecret?.accessToken)
        assertEquals("rotated-refresh", res.updatedSecret?.refreshToken)
        assertEquals(28.0, (res.lines[0] as MetricLine.Progress).used, 0.01)
    }

    @Test
    fun claudePropagates429RetryAfter() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
        val client = ClaudeClient(http, ProviderEndpointOverrides(mapOf("claude" to baseUrl)))
        try {
            client.probe(
                ConfiguredAccount("c1", ProviderId.CLAUDE.id),
                AccountSecret(accessToken = "valid-token"),
            )
            fail("Expected ProviderProbeException on 429")
        } catch (e: ProviderProbeException) {
            assertEquals(429, e.statusCode)
            assertNotNull(e.retryAfterMs)
            assertTrue(e.retryAfterMs!! >= 115_000L)
        }
    }

    @Test
    fun codexAndCopilotAndZaiParseCorrectly() = runTest {
        // Codex
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":12,"reset_after_seconds":3600,"limit_window_seconds":18000},"secondary_window":{"used_percent":40,"reset_after_seconds":86400,"limit_window_seconds":604800}}}""",
            ),
        )
        val codex = CodexClient(http, ProviderEndpointOverrides(mapOf("codex" to baseUrl)))
            .probe(ConfiguredAccount("x1", ProviderId.CODEX.id), AccountSecret(accessToken = "tok"))
        assertEquals("pro", codex.plan)
        assertEquals(12.0, (codex.lines[0] as MetricLine.Progress).used, 0.01)

        // Copilot
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"copilot_plan":"individual","quota_reset_date":"2026-12-01T00:00:00Z","quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":240,"unlimited":false},"chat":{"unlimited":true}}}""",
            ),
        )
        val copilot = CopilotClient(http, ProviderEndpointOverrides(mapOf("copilot" to baseUrl)))
            .probe(ConfiguredAccount("gh1", ProviderId.COPILOT.id), AccountSecret(accessToken = "ghp_xxx"))
        assertEquals("Individual", copilot.plan)
        assertEquals(60.0, (copilot.lines[0] as MetricLine.Progress).used, 0.01)

        // Z.ai
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"productName":"GLM Coding Pro"}]}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"percentage":18,"nextResetTime":1900000000000}]}}""",
            ),
        )
        val zai = ZaiClient(http, ProviderEndpointOverrides(mapOf("zai" to baseUrl)))
            .probe(ConfiguredAccount("z1", ProviderId.ZAI.id), AccountSecret(apiKey = "zai_key"))
        assertEquals("GLM Coding Pro", zai.plan)
        assertEquals(18.0, (zai.lines[0] as MetricLine.Progress).used, 0.01)
    }

}
