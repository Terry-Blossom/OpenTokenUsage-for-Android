package com.opentokenusage.android

import com.opentokenusage.android.data.network.clients.CodexClient
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodexClientTest {
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

    private fun client() = CodexClient(http, ProviderEndpointOverrides(mapOf("codex" to baseUrl)))

    @Test
    fun businessPlanUsesSpendControlUsedPercentWhenRateLimitIsNull() = runTest {
        val nowMs = 1_780_000_000_000L
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plan_type": "business",
                  "rate_limit": null,
                  "spend_control": {
                    "individual_limit": {
                      "limit": "2400",
                      "used": "1434.58",
                      "used_percent": 60,
                      "reset_at": 1789000000,
                      "reset_after_seconds": 11831
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client().probe(
            ConfiguredAccount("x1", ProviderId.CODEX.id),
            AccountSecret(accessToken = "tok"),
            nowMs = nowMs,
        )

        assertEquals("business", result.plan)
        val monthly = result.lines.single() as MetricLine.Progress
        assertEquals("Monthly", monthly.label)
        assertEquals(60.0, monthly.used, 0.01)
        assertEquals(100.0, monthly.limit, 0.01)
        assertEquals(MetricFormat.Percent, monthly.format)
        assertEquals("monthly", monthly.period)
        assertEquals(30L * 24L * 3600_000L, monthly.periodDurationMs)
        assertEquals(1_789_000_000_000L, monthly.resetsAtMs)
    }

    @Test
    fun businessPlanComputesPercentFromUsedAndLimitStringsAndFallsBackToResetAfter() = runTest {
        val nowMs = 1_780_000_000_000L
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plan_type": "business",
                  "rate_limit": null,
                  "spend_control": {
                    "individual_limit": {
                      "limit": "2400",
                      "used": "1440",
                      "reset_at": null,
                      "reset_after_seconds": 11831
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client().probe(
            ConfiguredAccount("x1", ProviderId.CODEX.id),
            AccountSecret(accessToken = "tok"),
            nowMs = nowMs,
        )

        val monthly = result.lines.single() as MetricLine.Progress
        assertEquals("Monthly", monthly.label)
        assertEquals(60.0, monthly.used, 0.01)
        assertEquals("monthly", monthly.period)
        assertEquals(30L * 24L * 3600_000L, monthly.periodDurationMs)
        assertEquals(nowMs + 11_831_000L, monthly.resetsAtMs)
    }

    @Test
    fun spendControlIsIgnoredWhenRateLimitIsPresent() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plan_type": "plus",
                  "rate_limit": {
                    "primary_window": { "used_percent": 15, "reset_after_seconds": 3600, "limit_window_seconds": 18000 }
                  },
                  "spend_control": {
                    "individual_limit": { "used_percent": 80, "reset_after_seconds": 86400 }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client().probe(
            ConfiguredAccount("x1", ProviderId.CODEX.id),
            AccountSecret(accessToken = "tok"),
        )

        assertTrue(result.lines.none { it.label == "Monthly" })
        assertEquals(1, result.lines.size)
        assertEquals("Session (5h)", result.lines.single().label)
    }

    @Test
    fun resetCreditsFetchesExpiryWhenAvailableCountPositive() = runTest {
        val nowMs = 1_780_000_000_000L
        val expSoonSec = 1_780_200_000L
        val expLaterSec = 1_780_800_000L
        val expPastSec = 1_779_900_000L

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plan_type": "plus",
                  "rate_limit": {
                    "primary_window": { "used_percent": 10, "reset_after_seconds": 3600, "limit_window_seconds": 18000 }
                  },
                  "rate_limit_reset_credits": { "available_count": 5 }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "available_count": 5,
                  "credits": [
                    { "status": "used", "expires_at": $expLaterSec },
                    { "status": "available", "expires_at": $expLaterSec },
                    { "status": "available", "expires_at": $expSoonSec },
                    { "status": "available", "expires_at": $expSoonSec },
                    { "status": "available", "expires_at": $expPastSec }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = client().probe(
            ConfiguredAccount("x1", ProviderId.CODEX.id),
            AccountSecret(accessToken = "tok", accountId = "acct-123"),
            nowMs = nowMs,
        )

        assertEquals(2, server.requestCount)
        assertEquals("/backend-api/wham/usage", server.takeRequest().path)
        val detailReq = server.takeRequest()
        assertEquals("/backend-api/wham/rate-limit-reset-credits", detailReq.path)
        assertEquals("Bearer tok", detailReq.getHeader("Authorization"))
        assertEquals("acct-123", detailReq.getHeader("ChatGPT-Account-Id"))

        val resetLine = result.lines.filterIsInstance<MetricLine.ResetCredit>().single()
        assertEquals("detail", resetLine.scope)
        // 2 at expSoon + 1 at expLater + 1 unknown (total 5 - 4 known available including 1 expired)
        assertEquals(4, resetLine.availableCount)
        assertEquals(3, resetLine.items.size)
        assertEquals(2, resetLine.items[0].count)
        assertEquals(expSoonSec * 1000L, resetLine.items[0].expiresAtMs)
        assertEquals(1, resetLine.items[1].count)
        assertEquals(expLaterSec * 1000L, resetLine.items[1].expiresAtMs)
        assertEquals(1, resetLine.items[2].count)
        assertNull(resetLine.items[2].expiresAtMs)

        // Locally removes expired items between probes without guessing unknown ones.
        val afterFirstExpiry = expSoonSec * 1000L + 1_000L
        assertEquals(2, resetLine.activeCount(afterFirstExpiry))
        assertEquals(2, resetLine.activeItems(afterFirstExpiry).size)
    }

    @Test
    fun resetCreditsDoesNotCallDetailEndpointWhenCountIsZero() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plan_type": "plus",
                  "rate_limit": {
                    "primary_window": { "used_percent": 10, "reset_after_seconds": 3600, "limit_window_seconds": 18000 }
                  },
                  "rate_limit_reset_credits": { "available_count": 0 }
                }
                """.trimIndent(),
            ),
        )

        val result = client().probe(
            ConfiguredAccount("x1", ProviderId.CODEX.id),
            AccountSecret(accessToken = "tok"),
        )

        assertEquals(1, server.requestCount)
        assertTrue(result.lines.filterIsInstance<MetricLine.ResetCredit>().isEmpty())
    }

    @Test
    fun resetCreditsKeepsSummaryCountWithUnknownExpiryWhenDetailFails() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plan_type": "pro",
                  "rate_limit": {
                    "primary_window": { "used_percent": 20, "reset_after_seconds": 3600, "limit_window_seconds": 18000 }
                  },
                  "rate_limit_reset_credits": { "available_count": 2 }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val result = client().probe(
            ConfiguredAccount("x1", ProviderId.CODEX.id),
            AccountSecret(accessToken = "tok"),
        )

        val resetLine = result.lines.filterIsInstance<MetricLine.ResetCredit>().single()
        assertEquals(2, resetLine.availableCount)
        assertEquals(1, resetLine.items.size)
        assertEquals(2, resetLine.items.single().count)
        assertNull(resetLine.items.single().expiresAtMs)
        assertNull(resetLine.expiresAtMs)
    }
}
