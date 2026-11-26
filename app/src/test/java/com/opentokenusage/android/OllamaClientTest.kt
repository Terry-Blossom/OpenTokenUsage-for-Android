package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.clients.OllamaClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OllamaClientTest {
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
    fun parsesVersionInstalledModelsAndLoadedModelsWithoutCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"0.13.0"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"name":"llama3.2:latest","size":2000000000},{"name":"qwen2.5:14b","size":9000000000}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"name":"qwen2.5:14b"}]}""",
            ),
        )

        val client = OllamaClient(http, ProviderEndpointOverrides())
        val res = client.probe(
            ConfiguredAccount("ol1", ProviderId.OLLAMA.id, customHost = baseUrl),
            AccountSecret(),
        )

        assertEquals("/api/version", server.takeRequest().path)
        assertEquals("/api/tags", server.takeRequest().path)
        assertEquals("/api/ps", server.takeRequest().path)

        assertEquals("v0.13.0", res.plan)
        assertEquals(2, res.lines.size)
        assertEquals("2 · 11.0 GB", (res.lines[0] as MetricLine.Text).value)
        assertEquals("qwen2.5:14b", (res.lines[1] as MetricLine.Text).value)
    }

    @Test
    fun showsDashWhenNoModelsLoadedAndToleratesMissingPs() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"0.13.1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))

        val client = OllamaClient(http, ProviderEndpointOverrides())
        val emptyLoaded = client.probe(
            ConfiguredAccount("ol2", ProviderId.OLLAMA.id, customHost = baseUrl),
            AccountSecret(),
        )
        assertEquals("0 · 0.0 GB", (emptyLoaded.lines[0] as MetricLine.Text).value)
        assertEquals("—", (emptyLoaded.lines[1] as MetricLine.Text).value)

        // /api/ps failure tolerated while /api/tags succeeds
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"0.13.1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"phi4","size":4500000000}]}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        val tagsOnly = client.probe(
            ConfiguredAccount("ol3", ProviderId.OLLAMA.id, customHost = baseUrl),
            AccountSecret(),
        )
        assertEquals(1, tagsOnly.lines.size)
        assertEquals("1 · 4.5 GB", (tagsOnly.lines[0] as MetricLine.Text).value)
    }

    @Test
    fun normalizesLanHostAndHandlesServerError() = runTest {
        assertEquals("http://192.168.1.50:11434", OllamaClient.normalizeBaseUrl("192.168.1.50:11434/"))
        assertEquals("https://ollama.lan", OllamaClient.normalizeBaseUrl("https://ollama.lan/"))

        server.enqueue(MockResponse().setResponseCode(503))
        val client = OllamaClient(http, ProviderEndpointOverrides())
        try {
            client.probe(
                ConfiguredAccount("ol4", ProviderId.OLLAMA.id, customHost = baseUrl),
                AccountSecret(),
            )
            fail("Expected error on 503")
        } catch (e: ProviderProbeException) {
            assertFalse(e.isAuthError)
            assertEquals(503, e.statusCode)
        }
    }
}
