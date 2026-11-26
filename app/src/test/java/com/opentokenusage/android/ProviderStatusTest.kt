package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderHttpSupport
import com.opentokenusage.android.data.network.ProviderStatusClient
import com.opentokenusage.android.domain.model.FetchFailureCause
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import com.opentokenusage.android.domain.model.ProviderServiceStatus
import com.opentokenusage.android.domain.model.ProviderSnapshot
import com.opentokenusage.android.domain.model.UsageDisplayMode
import com.opentokenusage.android.domain.model.diagnoseFetchFailure
import com.opentokenusage.android.util.formatStatusPreviewText
import com.opentokenusage.android.util.usageStatusEntries
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderStatusTest {
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
    fun parsesAtlassianStatuspageAndIncidentIoShapes() {
        val atlassian = """{"page":{"id":"x","name":"Claude","url":"https://status.claude.com"},
            "status":{"indicator":"major","description":"Partial System Outage"}}"""
        val parsedAtlassian = ProviderServiceStatus.parseStatusJson(atlassian, checkedAtMs = 1000L)
        assertEquals(
            ProviderServiceStatus(
                indicator = "major",
                description = "Partial System Outage",
                checkedAtMs = 1000L,
            ),
            parsedAtlassian,
        )
        assertTrue(parsedAtlassian!!.isOutage)

        val incidentIo = """{"page":{"id":"y"},"status":{"description":"All Systems Operational","indicator":"none"}}"""
        val parsedIncident = ProviderServiceStatus.parseStatusJson(incidentIo, checkedAtMs = 2000L)
        assertNotNull(parsedIncident)
        assertEquals("none", parsedIncident!!.indicator)
        assertEquals("All Systems Operational", parsedIncident.description)
        assertFalse(parsedIncident.isOutage)
    }

    @Test
    fun rejectsNonStatuspageBodies() {
        assertNull(ProviderServiceStatus.parseStatusJson("<!DOCTYPE html><html></html>"))
        assertNull(ProviderServiceStatus.parseStatusJson("""{"status":"ok"}"""))
        assertNull(ProviderServiceStatus.parseStatusJson("""{"status":{"indicator":""}}"""))
        assertNull(ProviderServiceStatus.parseStatusJson("""{"status":{"indicator":123,"description":"bad"}}"""))
    }

    @Test
    fun outageIndicatorDetectionMatchesStatuspageSpec() {
        assertFalse(ProviderServiceStatus.isOutageIndicator(null))
        assertFalse(ProviderServiceStatus.isOutageIndicator(""))
        assertFalse(ProviderServiceStatus.isOutageIndicator("none"))
        assertFalse(ProviderServiceStatus.isOutageIndicator("NONE"))
        assertTrue(ProviderServiceStatus.isOutageIndicator("minor"))
        assertTrue(ProviderServiceStatus.isOutageIndicator("major"))
        assertTrue(ProviderServiceStatus.isOutageIndicator("critical"))
        assertTrue(ProviderServiceStatus.isOutageIndicator("maintenance"))
    }

    @Test
    fun officialStatusPageUrlsAreConfigured() {
        assertEquals("https://status.claude.com", ProviderId.CLAUDE.statusPageUrl)
        assertEquals("https://status.openai.com", ProviderId.CODEX.statusPageUrl)
        assertEquals("https://status.cursor.com", ProviderId.CURSOR.statusPageUrl)
        assertEquals("https://www.githubstatus.com", ProviderId.COPILOT.statusPageUrl)
        assertEquals("https://status.deepseek.com", ProviderId.DEEPSEEK.statusPageUrl)
        assertEquals("https://status.x.ai", ProviderId.GROK.statusPageUrl)
    }

    @Test
    fun statusClientFetchesCachesAndExpiresAfterTtl() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":{"id":"p1"},"status":{"indicator":"minor","description":"Degraded Performance"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":{"id":"p1"},"status":{"indicator":"none","description":"All Systems Operational"}}""",
            ),
        )

        var now = 1_000_000L
        val client = ProviderStatusClient(
            httpClient = http,
            statusPageUrlProvider = { id -> if (id == "claude") baseUrl else null },
            cacheTtlMs = 5L * 60_000L,
            clockMs = { now },
        )

        val first = client.fetchStatus("claude")
        assertNotNull(first)
        assertEquals("minor", first!!.indicator)
        assertTrue(first.isOutage)
        assertEquals(1, server.requestCount)
        val req1 = server.takeRequest()
        assertEquals("/api/v2/status.json", req1.path)
        assertEquals("OpenTokenUsage", req1.getHeader("User-Agent"))

        // Still cached at 299s -> no second HTTP request
        now += 299_000L
        val cached = client.fetchStatus("claude")
        assertEquals(first, cached)
        assertEquals(1, server.requestCount)

        // Expired at 300s -> refetches from server
        now += 1_000L
        val refreshed = client.fetchStatus("claude")
        assertNotNull(refreshed)
        assertEquals("none", refreshed!!.indicator)
        assertFalse(refreshed.isOutage)
        assertEquals(2, server.requestCount)

        // Provider without statusPageUrl returns null without HTTP call
        assertNull(client.fetchStatus("antigravity"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun statusClientIgnoresHttpAndParseFailures() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!DOCTYPE html>"))

        val client = ProviderStatusClient(
            httpClient = http,
            statusPageUrlProvider = { baseUrl },
        )

        assertNull(client.fetchStatus("claude"))
        assertNull(client.fetchStatus("codex"))
    }

    @Test
    fun diagnosesServiceOutageVsClientAuthAndNetworkErrors() {
        val outage = ProviderServiceStatus(indicator = "major", description = "API Outage")
        val operational = ProviderServiceStatus(indicator = "none", description = "All Systems Operational")

        assertNull(diagnoseFetchFailure(null, outage))
        assertNull(diagnoseFetchFailure("   ", operational))

        val outageDiag = diagnoseFetchFailure(
            errorMessage = "Claude の取得に失敗 (HTTP 503)",
            serviceStatus = outage,
            isAuthError = false,
            statusCode = 503,
        )
        assertEquals(FetchFailureCause.SERVICE_OUTAGE, outageDiag?.cause)
        assertEquals("サービス側の障害の可能性", outageDiag?.summaryJa)
        assertEquals("API Outage", outageDiag?.serviceDescription)

        val authDiag = diagnoseFetchFailure(
            errorMessage = "トークンが期限切れです",
            serviceStatus = operational,
            isAuthError = true,
            statusCode = 401,
        )
        assertEquals(FetchFailureCause.AUTH, authDiag?.cause)
        assertTrue(authDiag!!.summaryJa.contains("認証"))

        val networkDiag = diagnoseFetchFailure(
            errorMessage = "通信エラー: Unable to resolve host",
            serviceStatus = null,
            isAuthError = false,
            statusCode = null,
        )
        assertEquals(FetchFailureCause.NETWORK, networkDiag?.cause)
        assertTrue(networkDiag!!.summaryJa.contains("ネットワーク"))

        val otherDiag = diagnoseFetchFailure(
            errorMessage = "Claude のレスポンス解析に失敗",
            serviceStatus = operational,
            isAuthError = false,
            statusCode = 500,
        )
        assertEquals(FetchFailureCause.LOCAL_OR_OTHER, otherDiag?.cause)
        assertFalse(otherDiag!!.summaryJa.contains("サービス側の障害"))
    }

    @Test
    fun persistentNotificationPreviewMarksOutageProviders() {
        val claudeOutage = ProviderSnapshot(
            accountId = "a1",
            providerId = "claude",
            displayName = "Claude",
            fetchedAtMs = 1L,
            lines = listOf(MetricLine.Progress("Session (5h)", 20.0, 100.0, primaryOrder = 1)),
            serviceStatus = ProviderServiceStatus(indicator = "major", description = "Partial Outage"),
        )
        val codexHealthy = ProviderSnapshot(
            accountId = "a2",
            providerId = "codex",
            displayName = "Codex",
            fetchedAtMs = 1L,
            lines = listOf(MetricLine.Progress("Session", 30.0, 100.0, primaryOrder = 1)),
            serviceStatus = ProviderServiceStatus(indicator = "none", description = "All Systems Operational"),
        )

        val preview = formatStatusPreviewText(
            entries = usageStatusEntries(listOf(claudeOutage, codexHealthy)),
            displayMode = UsageDisplayMode.LEFT,
        )
        assertEquals("⚠障害 Claude 80% · Codex 70%", preview)
    }

    @Test
    fun snapshotSerializationBackwardCompatibleWithServiceStatus() {
        val legacyJson = """{"accountId":"a1","providerId":"claude","displayName":"Claude","fetchedAtMs":10}"""
        val decoded = ProviderHttpSupport.json.decodeFromString<ProviderSnapshot>(legacyJson)
        assertNull(decoded.serviceStatus)
        assertFalse(decoded.errorIsAuth)
        assertNull(decoded.errorStatusCode)

        val withStatus = decoded.copy(
            errorMessage = "HTTP 503",
            errorIsAuth = false,
            errorStatusCode = 503,
            serviceStatus = ProviderServiceStatus(indicator = "critical", description = "Major Outage", checkedAtMs = 99L),
        )
        val roundTripped = ProviderHttpSupport.json.decodeFromString<ProviderSnapshot>(
            ProviderHttpSupport.json.encodeToString(withStatus),
        )
        assertEquals(withStatus, roundTripped)
    }
}
