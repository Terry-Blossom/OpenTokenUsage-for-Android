package com.opentokenusage.android

import com.opentokenusage.android.data.network.clients.AmpClient
import com.opentokenusage.android.data.network.clients.AntigravityClient
import com.opentokenusage.android.data.network.clients.DeepSeekClient
import com.opentokenusage.android.data.network.clients.MiniMaxClient
import com.opentokenusage.android.data.network.clients.OpenCodeGoClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.data.network.clients.SyntheticClient
import com.opentokenusage.android.domain.logic.CurrencyFormatter
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.MiniMaxRegion
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ResetTimerMode
import com.opentokenusage.android.domain.model.UsageDisplayMode
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

class MoreClientsAndFormattersTest {
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
    fun formattersSupportCurrencyLeftUsedAndResetModes() {
        val jpy = CurrencyFormatter.formatUsdAmount(10.0, "JPY", manualRate = 150.0)
        assertEquals("¥1,500", jpy)

        val settings = AppSettings(displayMode = UsageDisplayMode.LEFT, resetTimerMode = ResetTimerMode.RELATIVE)
        val formattedLeft = UsageDisplayFormatter.formatMetricValue(
            used = 25.0,
            limit = 100.0,
            format = com.opentokenusage.android.domain.model.MetricFormat.Percent,
            settings = settings,
        )
        assertEquals("残り 75%", formattedLeft)

        val absReset = UsageDisplayFormatter.formatResetTime(
            resetsAtMs = 1_700_050_000_000L,
            nowMs = 1_700_000_000_000L,
            mode = ResetTimerMode.ABSOLUTE,
            clock24h = true,
            zoneId = ZoneOffset.UTC,
        )
        assertTrue(absReset!!.contains(":"))
    }

    @Test
    fun deepSeekMiniMaxSyntheticOpenCodeAmpClientsWork() = runTest {
        // DeepSeek
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"12.34","topped_up_balance":"10.00","granted_balance":"2.34"}]}""",
            ),
        )
        val ds = DeepSeekClient(http, ProviderEndpointOverrides(mapOf("deepseek" to baseUrl)))
            .probe(ConfiguredAccount("d1", ProviderId.DEEPSEEK.id), AccountSecret(apiKey = "ds"))
        assertEquals("$12.34", (ds.lines[0] as MetricLine.Text).value)

        // MiniMax Global
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"base_resp":{"status_code":0},"model_remains":[{"current_interval_total_count":1000,"current_interval_usage_count":750,"remains_time":3600}]}""",
            ),
        )
        val mm = MiniMaxClient(http, ProviderEndpointOverrides(mapOf("minimax" to baseUrl)))
            .probe(ConfiguredAccount("m1", ProviderId.MINIMAX.id, region = MiniMaxRegion.GLOBAL), AccountSecret(apiKey = "mm"))
        assertEquals(250.0, (mm.lines[0] as MetricLine.Progress).used, 0.01)

        // Synthetic
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"rollingFiveHourLimit":{"max":135,"remaining":100},"weeklyTokenLimit":{"percentRemaining":80.0}}""",
            ),
        )
        val syn = SyntheticClient(http, ProviderEndpointOverrides(mapOf("synthetic" to baseUrl)))
            .probe(ConfiguredAccount("s1", ProviderId.SYNTHETIC.id), AccountSecret(apiKey = "syn"))
        assertEquals(35.0, (syn.lines[0] as MetricLine.Progress).used, 0.01)
        assertEquals(20.0, (syn.lines[1] as MetricLine.Progress).used, 0.01)

        // OpenCode Go
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usage":{"rolling":{"percent":14,"resetsAt":"2026-11-15T12:00:00Z"},"weekly":{"percent":33,"resetsAt":"2026-11-20T00:00:00Z"},"monthly":{"percent":58,"resetsAt":"2026-12-01T00:00:00Z"}}}""",
            ),
        )
        val oc = OpenCodeGoClient(http, ProviderEndpointOverrides(mapOf("opencode-go" to baseUrl)))
            .probe(ConfiguredAccount("o1", ProviderId.OPENCODE_GO.id), AccountSecret(apiKey = "oc"))
        assertEquals(14.0, (oc.lines[0] as MetricLine.Progress).used, 0.01)
        assertEquals(listOf("Session", "Weekly", "Monthly"), oc.lines.map { it.label })
        assertEquals("detail", oc.lines[2].scope)

        // Amp
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"result":{"displayText":"Amp Free: $8.00/$10.00 remaining (replenishes +$0.50/hour)"}}""",
            ),
        )
        val amp = AmpClient(http, ProviderEndpointOverrides(mapOf("amp" to baseUrl)))
            .probe(ConfiguredAccount("a1", ProviderId.AMP.id), AccountSecret(apiKey = "amp"))
        assertEquals(2.0, (amp.lines[0] as MetricLine.Progress).used, 0.01)
    }

    @Test
    fun antigravityMergesSummaryBucketsIncludingArgon() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"cloudaicompanionProject":"proj-1","paidTier":{"id":"g1-ultra-tier"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "response": {
                    "groups": [
                      {
                        "displayName": "Shared",
                        "buckets": [
                          {"bucketId": "gemini-5h", "remainingFraction": 0.75, "resetTime": "2026-11-15T15:00:00Z"},
                          {"bucketId": "3p-5h", "remainingFraction": 0.50, "resetTime": "2026-11-15T15:00:00Z"}
                        ]
                      },
                      {
                        "displayName": "Gemini 4 Argon",
                        "buckets": [
                          {"bucketId": "argon-weekly", "window": "weekly", "remainingFraction": 0.90, "resetTime": "2026-11-20T00:00:00Z"}
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        val agy = AntigravityClient(
            http,
            ProviderEndpointOverrides(mapOf("antigravity" to baseUrl, "antigravity-daily" to baseUrl)),
        ).probe(ConfiguredAccount("ag1", ProviderId.ANTIGRAVITY.id), AccountSecret(accessToken = "ya29.test"))

        assertEquals("Google AI Ultra", agy.plan)
        assertEquals(3, agy.lines.size)
        assertEquals("Session", agy.lines[0].label)
        assertEquals(25.0, (agy.lines[0] as MetricLine.Progress).used, 0.01)
        assertEquals("Argon Weekly", agy.lines[1].label)
        assertEquals(10.0, (agy.lines[1] as MetricLine.Progress).used, 0.01)
        assertEquals("Claude", agy.lines[2].label)
        assertEquals(50.0, (agy.lines[2] as MetricLine.Progress).used, 0.01)
    }
}
