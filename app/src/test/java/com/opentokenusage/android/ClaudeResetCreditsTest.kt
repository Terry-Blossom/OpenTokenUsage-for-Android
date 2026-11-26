package com.opentokenusage.android

import com.opentokenusage.android.data.network.clients.ClaudeClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.data.storage.InMemoryClaudeResetGrantsCache
import com.opentokenusage.android.domain.logic.UsageDisplayFormatter
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

class ClaudeResetCreditsTest {
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
    fun fetchesCedarEmberGrantsAndExcludesSpentExpiredPausedUnusable() = runTest {
        val nowMs = 1_795_000_000_000L // 2026-11-18T...
        val cache = InMemoryClaudeResetGrantsCache()
        val client = ClaudeClient(http, ProviderEndpointOverrides(mapOf("claude" to baseUrl)), cache)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"five_hour":{"utilization":20,"resets_at":"2026-11-18T18:00:00Z"},"seven_day":{"utilization":30,"resets_at":"2026-11-24T00:00:00Z"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"organization":{"plan_display_name":"Max"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "cedar_ember": {
                    "eligible": true,
                    "grants": [
                      { "resets_left": 0, "ends_at": "2026-11-28T00:00:00Z", "type": "weekly" },
                      { "resets_left": 1, "ends_at": "2026-11-28T00:00:00Z", "paused": true, "type": "weekly" },
                      { "resets_left": 1, "ends_at": "2026-11-28T00:00:00Z", "usable_now": false, "type": "weekly" },
                      { "resets_left": 1, "ends_at": "2026-11-01T00:00:00Z", "type": "session" },
                      { "resets_left": 1, "ends_at": "2026-11-25T12:00:00Z", "gives_back_weekly": true },
                      { "resets_left": 2, "ends_at": "2026-11-25T12:00:00Z", "clears": ["seven_day"] },
                      { "resets_left": 1, "ends_at": "2026-11-25T12:00:00Z", "type": "weekly" },
                      { "resets_left": 1, "clears": ["five_hour"] }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client.probe(
            ConfiguredAccount("c1", ProviderId.CLAUDE.id),
            AccountSecret(accessToken = "tok"),
            nowMs = nowMs,
        )

        assertEquals(3, server.requestCount)
        assertEquals("/api/oauth/usage", server.takeRequest().path)
        assertEquals("/api/oauth/profile", server.takeRequest().path)
        assertEquals("/api/oauth/usage?cedar_ember=1&skip_spend=1", server.takeRequest().path)

        val resetLine = result.lines.filterIsInstance<MetricLine.ResetCredit>().single()
        assertEquals(5, resetLine.availableCount)
        assertEquals(3, resetLine.items.size)

        // Sorted by expiry ascending (fable vs weekly at same expiry kept separate, two weekly grouped into 3)
        val fable = resetLine.items.first { it.kind == "fable" }
        assertEquals(1, fable.count)
        assertEquals(1_795_608_000_000L, fable.expiresAtMs)

        val weekly = resetLine.items.first { it.kind == "weekly" }
        assertEquals(3, weekly.count)
        assertEquals(1_795_608_000_000L, weekly.expiresAtMs)

        val sessionUnknownExpiry = resetLine.items.first { it.kind == "session" }
        assertEquals(1, sessionUnknownExpiry.count)
        assertNull(sessionUnknownExpiry.expiresAtMs)
    }

    @Test
    fun cachesResetGrantsWithinIntervalAndPreservesCacheOn429() = runTest {
        val nowMs = 1_795_000_000_000L
        val cache = InMemoryClaudeResetGrantsCache()
        val client = ClaudeClient(http, ProviderEndpointOverrides(mapOf("claude" to baseUrl)), cache)

        // First probe fetches cedar_ember
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"five_hour":{"utilization":10,"resets_at":"2026-11-18T18:00:00Z"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"organization":{"plan_display_name":"Pro"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"cedar_ember":{"eligible":true,"grants":[{"resets_left":2,"ends_at":"2026-11-25T12:00:00Z","type":"full"}]}}""",
            ),
        )
        val first = client.probe(ConfiguredAccount("c1", ProviderId.CLAUDE.id), AccountSecret(accessToken = "tok"), nowMs = nowMs)
        assertEquals(2, first.lines.filterIsInstance<MetricLine.ResetCredit>().single().availableCount)
        assertEquals(3, server.requestCount)

        // Second probe 10 minutes later reuses cached grants (no ?cedar_ember=1 call)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"five_hour":{"utilization":20,"resets_at":"2026-11-18T18:00:00Z"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"organization":{"plan_display_name":"Pro"}}"""))
        val second = client.probe(
            ConfiguredAccount("c1", ProviderId.CLAUDE.id),
            AccountSecret(accessToken = "tok"),
            nowMs = nowMs + 10L * 60_000L,
        )
        assertEquals(5, server.requestCount)
        assertEquals(2, second.lines.filterIsInstance<MetricLine.ResetCredit>().single().availableCount)

        // Third probe 65 minutes later retries ?cedar_ember=1; on 429 keeps cached grant without failing probe
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"five_hour":{"utilization":30,"resets_at":"2026-11-18T18:00:00Z"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"organization":{"plan_display_name":"Pro"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "3000"))
        val third = client.probe(
            ConfiguredAccount("c1", ProviderId.CLAUDE.id),
            AccountSecret(accessToken = "tok"),
            nowMs = nowMs + 65L * 60_000L,
        )
        assertEquals(8, server.requestCount)
        assertEquals(2, third.lines.filterIsInstance<MetricLine.ResetCredit>().single().availableCount)
    }

    @Test
    fun formatsResetCreditExpiryWithoutGuessingMissingDates() {
        val nowMs = 1_795_000_000_000L
        assertEquals("期限不明", UsageDisplayFormatter.formatResetCreditExpiryJa(null, nowMs, clock24h = true, ZoneOffset.UTC))
        assertEquals("期限切れ", UsageDisplayFormatter.formatResetCreditExpiryJa(nowMs - 1000L, nowMs, clock24h = true, ZoneOffset.UTC))
        val futureText = UsageDisplayFormatter.formatResetCreditExpiryJa(
            nowMs + 2L * 24L * 3600_000L + 3L * 3600_000L,
            nowMs,
            clock24h = true,
            ZoneOffset.UTC,
        )
        assertTrue(futureText.startsWith("あと2日3時間 ("))
        assertTrue(futureText.endsWith(" まで)"))
    }
}
