package com.opentokenusage.android

import com.opentokenusage.android.data.network.clients.CursorClient
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
import org.junit.Before
import org.junit.Test

class CursorClientTest {
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

    private fun client() = CursorClient(
        http,
        ProviderEndpointOverrides(mapOf("cursor" to baseUrl, "cursor-web" to baseUrl)),
    )

    @Test
    fun exchangesCursorApiKeyBeforeReadingUsage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"cursor-session","refreshToken":"cursor-refresh"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"billingCycleStart":"2026-10-18T06:32:13Z","billingCycleEnd":"2026-10-25T06:32:13Z","planUsage":{"totalPercentUsed":24}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"isMerged":true,"planInfo":{"planName":"Premium"}}""",
            ),
        )

        val result = client().probe(
            ConfiguredAccount("c1", ProviderId.CURSOR.id),
            AccountSecret(apiKey = "crsr_test_key"),
        )

        val exchange = server.takeRequest()
        assertEquals("/auth/exchange_user_api_key", exchange.path)
        assertEquals("Bearer crsr_test_key", exchange.getHeader("Authorization"))
        assertEquals("cursor-session", result.updatedSecret?.accessToken)
        assertEquals(24.0, (result.lines.single() as MetricLine.Progress).used, 0.01)
    }

    @Test
    fun xPlanShowsOnlyTotalUsage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "billingCycleStart": "2026-10-18T06:32:13.504Z",
                  "billingCycleEnd": "2026-10-25T06:32:13.504Z",
                  "planUsage": {
                    "totalPercentUsed": 42.5,
                    "autoPercentUsed": 40.0,
                    "apiPercentUsed": 2.5,
                    "limit": 0,
                    "remaining": 0,
                    "totalSpend": 0
                  },
                  "spendLimitUsage": {
                    "individualLimit": 5000,
                    "individualRemaining": 5000
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"isMerged":true,"planInfo":{"planName":"Ultra","includedUsagePeriod":"INCLUDED_USAGE_PERIOD_WEEKLY"}}""",
            ),
        )

        val result = client().probe(
            ConfiguredAccount("c1", ProviderId.CURSOR.id),
            AccountSecret(accessToken = "tok"),
        )

        assertEquals("Ultra", result.plan)
        assertEquals(1, result.lines.size)
        val only = result.lines.single() as MetricLine.Progress
        assertEquals("Total usage", only.label)
        assertEquals(42.5, only.used, 0.01)
        assertEquals(100.0, only.limit, 0.01)
        assertEquals(MetricFormat.Percent, only.format)
        assertEquals("weekly", only.period)
    }

    @Test
    fun premiumPlanNameShowsOnlyTotalUsageEvenWhenIsMergedIsFalse() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"billingCycleStart":"2026-10-18T06:32:13Z","billingCycleEnd":"2026-10-25T06:32:13Z","planUsage":{"totalPercentUsed":35,"limit":0},"spendLimitUsage":{"individualLimit":5000,"individualRemaining":4000}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"isMerged":false,"planInfo":{"planName":"Premium"}}""",
            ),
        )

        val result = client().probe(
            ConfiguredAccount("c1", ProviderId.CURSOR.id),
            AccountSecret(accessToken = "tok"),
        )

        assertEquals(1, result.lines.size)
        val only = result.lines.single() as MetricLine.Progress
        assertEquals("Total usage", only.label)
        assertEquals(35.0, only.used, 0.01)
    }

    @Test
    fun legacyPlanKeepsDetailedUsageLines() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "billingCycleStart": "2026-10-01T00:00:00Z",
                  "billingCycleEnd": "2026-11-01T00:00:00Z",
                  "planUsage": {
                    "limit": 2000,
                    "remaining": 1200,
                    "totalSpend": 800,
                    "autoPercentUsed": 40
                  },
                  "spendLimitUsage": {
                    "individualLimit": 5000,
                    "individualRemaining": 4000,
                    "individualUsed": 1000
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"isMerged":false,"planInfo":{"planName":"Pro"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client().probe(
            ConfiguredAccount("c1", ProviderId.CURSOR.id),
            AccountSecret(accessToken = "tok"),
        )

        assertEquals("Pro", result.plan)
        assertEquals(2, result.lines.size)
        val planUsage = result.lines[0] as MetricLine.Progress
        assertEquals("Plan usage", planUsage.label)
        assertEquals(8.0, planUsage.used, 0.01)
        assertEquals(20.0, planUsage.limit, 0.01)
        val onDemand = result.lines[1] as MetricLine.Progress
        assertEquals("On-demand", onDemand.label)
        assertEquals(10.0, onDemand.used, 0.01)
        assertEquals(50.0, onDemand.limit, 0.01)
    }

    @Test
    fun xPlanIsInferredWhenPlanInfoAndMembershipAreUnavailable() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "billingCycleStart": "2026-10-18T06:32:13.504Z",
                  "billingCycleEnd": "2026-10-25T06:32:13.504Z",
                  "planUsage": {
                    "totalPercentUsed": 18.0,
                    "autoPercentUsed": 18.0
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client().probe(
            ConfiguredAccount("c1", ProviderId.CURSOR.id),
            AccountSecret(accessToken = "tok"),
        )

        assertEquals(1, result.lines.size)
        val only = result.lines.single() as MetricLine.Progress
        assertEquals("Total usage", only.label)
        assertEquals(18.0, only.used, 0.01)
        assertEquals("weekly", only.period)
    }

    @Test
    fun xPlanMembershipIsDetectedWhenPlanInfoFails() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "billingCycleStart": "2026-10-18T06:32:13.504Z",
                  "billingCycleEnd": "2026-10-25T06:32:13.504Z",
                  "planUsage": { "autoPercentUsed": 50.0 }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "membershipType": "premium",
                  "billingCycleEnd": "2026-10-25T06:32:13.504Z",
                  "individualUsage": {
                    "plan": { "totalPercentUsed": 33.0, "autoPercentUsed": 33.0 }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client().probe(
            ConfiguredAccount("c1", ProviderId.CURSOR.id),
            AccountSecret(accessToken = "tok", sessionCookie = "user%3A%3Atok"),
        )

        assertEquals("Premium", result.plan)
        assertEquals(1, result.lines.size)
        val only = result.lines.single() as MetricLine.Progress
        assertEquals("Total usage", only.label)
        assertEquals(33.0, only.used, 0.01)
        assertEquals("weekly", only.period)
    }
}
