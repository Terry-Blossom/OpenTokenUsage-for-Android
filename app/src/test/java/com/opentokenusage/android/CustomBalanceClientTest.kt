package com.opentokenusage.android

import com.opentokenusage.android.data.network.ProviderProbeException
import com.opentokenusage.android.data.network.clients.CustomBalanceClient
import com.opentokenusage.android.data.network.clients.ProviderEndpointOverrides
import com.opentokenusage.android.domain.logic.SettingsBackupCodec
import com.opentokenusage.android.domain.model.AccountSecret
import com.opentokenusage.android.domain.model.AppSettings
import com.opentokenusage.android.domain.model.ConfiguredAccount
import com.opentokenusage.android.domain.model.CustomBalanceAuthHeader
import com.opentokenusage.android.domain.model.CustomBalanceConfig
import com.opentokenusage.android.domain.model.CustomBalancePreset
import com.opentokenusage.android.domain.model.MetricLine
import com.opentokenusage.android.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CustomBalanceClientTest {
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
    fun newApiAccountStripsV1SendsUserHeaderAndDividesByQuotaPerUnit() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"quota":6000000,"used_quota":1500000,"unlimited_quota":false}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"quota_per_unit":500000}}""",
            ),
        )

        val client = CustomBalanceClient(http, ProviderEndpointOverrides())
        val account = ConfiguredAccount(
            id = "cb-1",
            providerId = ProviderId.CUSTOM_BALANCE.id,
            label = "One API",
            customHost = "$baseUrl/v1",
            customBalance = CustomBalanceConfig(
                preset = CustomBalancePreset.NEW_API,
                userId = "42",
            ),
        )
        val result = client.probe(account, AccountSecret(apiKey = "sk-newapi"))

        val selfReq = server.takeRequest()
        assertEquals("/api/user/self", selfReq.path)
        assertEquals("Bearer sk-newapi", selfReq.getHeader("Authorization"))
        assertEquals("42", selfReq.getHeader("New-Api-User"))
        assertEquals("/api/status", server.takeRequest().path)

        assertEquals("New API", result.plan)
        val balanceLine = result.lines.first() as MetricLine.Text
        assertEquals("$12.00 / $15.00", balanceLine.value)
        assertEquals(null, balanceLine.colorHex)
    }

    @Test
    fun newApiTokenFallsBackToDefaultUnitAndHandlesUnlimited() = runTest {
        // Finite quota with /api/status failing -> default 500000 unit
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"total_available":2500000,"total_used":500000,"unlimited_quota":false}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val client = CustomBalanceClient(http, ProviderEndpointOverrides())
        val tokenAccount = ConfiguredAccount(
            id = "cb-2",
            providerId = ProviderId.CUSTOM_BALANCE.id,
            label = "Relay Key",
            customHost = baseUrl,
            customBalance = CustomBalanceConfig(preset = CustomBalancePreset.NEW_API_TOKEN),
        )
        val res = client.probe(tokenAccount, AccountSecret(apiKey = "sk-token"))
        assertEquals("/api/usage/token/", server.takeRequest().path)
        assertEquals("/api/status", server.takeRequest().path)
        assertEquals("New API Key", res.plan)
        assertEquals("$5.00 / $6.00", (res.lines.first() as MetricLine.Text).value)

        // Unlimited quota
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"unlimited_quota":true}}""",
            ),
        )
        val unlimited = client.probe(tokenAccount, AccountSecret(apiKey = "sk-token"))
        assertEquals("∞", (unlimited.lines.first() as MetricLine.Text).value)
    }

    @Test
    fun sub2ApiParsesBalanceAndMarksZeroRed() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":0,"data":{"balance":0}}""",
            ),
        )
        val client = CustomBalanceClient(http, ProviderEndpointOverrides())
        val account = ConfiguredAccount(
            id = "cb-3",
            providerId = ProviderId.CUSTOM_BALANCE.id,
            label = "Sub2API",
            customHost = "$baseUrl/v1",
            customBalance = CustomBalanceConfig(preset = CustomBalancePreset.SUB2API),
        )
        val res = client.probe(account, AccountSecret(apiKey = "sub2-key"))
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/me", req.path)
        assertEquals("Bearer sub2-key", req.getHeader("Authorization"))
        assertEquals("Sub2API", res.plan)
        val line = res.lines.first() as MetricLine.Text
        assertEquals("$0.00", line.value)
        assertEquals("#EF4444", line.colorHex)
    }

    @Test
    fun customEndpointReadsDotPathsDivisorCurrencyAndXApiKey() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"wallet":{"credits":{"left":"123400","total":200000}}}""",
            ),
        )
        val client = CustomBalanceClient(http, ProviderEndpointOverrides())
        val account = ConfiguredAccount(
            id = "cb-4",
            providerId = ProviderId.CUSTOM_BALANCE.id,
            label = "Custom",
            customHost = baseUrl,
            customBalance = CustomBalanceConfig(
                preset = CustomBalancePreset.CUSTOM,
                path = "/v2/wallet",
                authHeader = CustomBalanceAuthHeader.X_API_KEY,
                remainingPath = "wallet.credits.left",
                totalPath = "wallet.credits.total",
                divisor = 100.0,
                currency = "JPY",
            ),
        )
        val res = client.probe(account, AccountSecret(apiKey = "x-key-123"))
        val req = server.takeRequest()
        assertEquals("/v2/wallet", req.path)
        assertEquals("x-key-123", req.getHeader("x-api-key"))
        assertEquals(null, req.getHeader("Authorization"))
        assertEquals("JPY", res.plan)
        assertEquals("1234.00 JPY / 2000.00 JPY", (res.lines.first() as MetricLine.Text).value)
    }

    @Test
    fun multipleAccountsWorkIndependentlyAndBackupExcludesApiKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0,"data":{"balance":12.5}}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        val client = CustomBalanceClient(http, ProviderEndpointOverrides())
        val accA = ConfiguredAccount(
            id = "cb-a",
            providerId = ProviderId.CUSTOM_BALANCE.id,
            label = "Panel A",
            customHost = baseUrl,
            customBalance = CustomBalanceConfig(preset = CustomBalancePreset.SUB2API),
        )
        val accB = ConfiguredAccount(
            id = "cb-b",
            providerId = ProviderId.CUSTOM_BALANCE.id,
            label = "Panel B",
            customHost = baseUrl,
            customBalance = CustomBalanceConfig(preset = CustomBalancePreset.SUB2API),
        )

        val resA = client.probe(accA, AccountSecret(apiKey = "secret-a"))
        assertEquals("$12.50", (resA.lines.first() as MetricLine.Text).value)

        try {
            client.probe(accB, AccountSecret(apiKey = "secret-b"))
            fail("Expected auth error on 401")
        } catch (e: ProviderProbeException) {
            assertTrue(e.isAuthError)
            assertEquals(401, e.statusCode)
        }

        // Settings backup preserves customBalance metadata without leaking API keys
        val exported = SettingsBackupCodec.exportJson(AppSettings(), listOf(accA, accB))
        assertFalse(exported.contains("secret-a"))
        assertFalse(exported.contains("secret-b"))
        val parsed = SettingsBackupCodec.importJson(exported).getOrThrow()
        val merged = SettingsBackupCodec.mergeBackup(emptyList(), AppSettings(), parsed, nowMs = 0L)
        assertEquals(2, merged.accounts.size)
        assertEquals(CustomBalancePreset.SUB2API, merged.accounts[0].customBalance?.preset)
        assertEquals(baseUrl, merged.accounts[0].customHost)
    }
}
