package com.rjnr.pocketnode.data.price

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gating tests for the "Fiat price" network-services switch (#531): with the
 * switch off, [PriceRepository] must make zero HTTP requests and hand back a
 * distinguishable failure so the UI can hide the fiat line instead of
 * showing a stale value. With the switch on, existing behaviour (CoinGecko
 * primary, Binance fallback) is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PriceRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ckb_wallet_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun newWalletPreferences(): WalletPreferences = WalletPreferences(context, NoopLogger)

    private fun repositoryWith(
        walletPreferences: WalletPreferences,
        handler: (HttpRequestData) -> Pair<String, HttpStatusCode>,
    ): Pair<PriceRepository, () -> Int> {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            val (body, status) = handler(request)
            respond(content = body, status = status)
        }
        val client = HttpClient(engine)
        val repository = PriceRepository(client, json, context, NoopLogger, walletPreferences)
        return repository to { requestCount }
    }

    @Test
    fun `makes no HTTP request and fails with a distinct exception when the price service is off`() = runTest {
        val prefs = newWalletPreferences()
        prefs.setPriceServiceEnabled(false)
        val (repository, requestCount) = repositoryWith(prefs) { "{}" to HttpStatusCode.OK }

        val result = repository.getCkbUsdPrice()

        assertEquals("no network request should be made", 0, requestCount())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PriceServiceDisabledException)
    }

    @Test
    fun `fetches from CoinGecko when the price service is on`() = runTest {
        val prefs = newWalletPreferences()
        val (repository, requestCount) = repositoryWith(prefs) { request ->
            assertTrue(request.url.toString().contains("coingecko"))
            """{"nervos-network":{"usd":0.0051}}""" to HttpStatusCode.OK
        }

        val result = repository.getCkbUsdPrice()

        assertEquals(1, requestCount())
        assertTrue(result.isSuccess)
        assertEquals(0.0051, result.getOrThrow(), 0.00001)
    }

    @Test
    fun `falls back to Binance when CoinGecko's response is unusable, unaffected by the toggle being on`() = runTest {
        val prefs = newWalletPreferences()
        val (repository, requestCount) = repositoryWith(prefs) { request ->
            if (request.url.toString().contains("coingecko")) {
                // Well-formed JSON, but missing the expected key: fetchFromCoinGecko
                // throws "CKB price not found", triggering the Binance fallback.
                "{}" to HttpStatusCode.OK
            } else {
                """{"symbol":"CKBUSDT","price":"0.00499000"}""" to HttpStatusCode.OK
            }
        }

        val result = repository.getCkbUsdPrice()

        assertEquals(2, requestCount())
        assertTrue(result.isSuccess)
        assertEquals(0.00499, result.getOrThrow(), 0.00001)
    }
}
