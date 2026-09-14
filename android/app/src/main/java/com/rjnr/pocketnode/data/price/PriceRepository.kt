package com.rjnr.pocketnode.data.price

import android.content.Context
import android.content.SharedPreferences
import com.rjnr.pocketnode.core.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the CKB/USD spot price.
 *
 * Resilience tiers (#117 — gp on Xiaomi 15 Pro saw "USD: -" because CoinGecko
 * is unreliable in some regions):
 *
 *   1. CoinGecko `/simple/price` (primary).
 *   2. Binance `/api/v3/ticker/price?symbol=CKBUSDT` (fallback — globally reliable).
 *   3. Last cached price from SharedPreferences (≤ 24h old) when both live
 *      endpoints fail.
 *
 * Cache is persisted on every successful fetch so subsequent sessions can
 * survive an offline / blocked-endpoint start.
 */
@Singleton
class PriceRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    @ApplicationContext context: Context,
    private val logger: Logger,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getCkbUsdPrice(): Result<Double> = withContext(Dispatchers.IO) {
        // Try primary, then fallback. Each is its own runCatching so a failure
        // in one path doesn't shadow the success of the next.
        val primary = runCatching { fetchFromCoinGecko() }
        if (primary.isSuccess) {
            val price = primary.getOrThrow()
            cachePrice(price)
            return@withContext Result.success(price)
        }
        logger.w(TAG, "CoinGecko fetch failed: ${primary.exceptionOrNull()?.message}; trying Binance")

        val fallback = runCatching { fetchFromBinance() }
        if (fallback.isSuccess) {
            val price = fallback.getOrThrow()
            cachePrice(price)
            return@withContext Result.success(price)
        }
        logger.w(TAG, "Binance fetch failed: ${fallback.exceptionOrNull()?.message}; checking cache")

        val cached = cachedPriceIfFresh()
        if (cached != null) {
            logger.d(TAG, "Using cached CKB/USD price: $cached")
            return@withContext Result.success(cached)
        }

        // Both live endpoints failed and no fresh cache → propagate the original
        // CoinGecko failure (more useful than Binance's, since CoinGecko is the
        // primary path users expect).
        Result.failure(primary.exceptionOrNull() ?: Exception("Price fetch failed"))
    }

    private suspend fun fetchFromCoinGecko(): Double {
        // CancellationException from inside `try` would otherwise be swallowed —
        // re-throw at the call boundary. (Outer runCatching propagates Result.failure
        // for everything else.)
        try {
            val response = httpClient.get("https://api.coingecko.com/api/v3/simple/price") {
                parameter("ids", "nervos-network")
                parameter("vs_currencies", "usd")
            }
            val body = response.bodyAsText()
            val data = json.decodeFromString<Map<String, Map<String, Double>>>(body)
            return data["nervos-network"]?.get("usd")
                ?: throw Exception("CKB price not found in CoinGecko response")
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun fetchFromBinance(): Double {
        try {
            val response = httpClient.get("https://api.binance.com/api/v3/ticker/price") {
                parameter("symbol", "CKBUSDT")
            }
            val body = response.bodyAsText()
            // Response shape: {"symbol":"CKBUSDT","price":"0.00499000"}
            val obj = json.parseToJsonElement(body).jsonObject
            val priceStr = obj["price"]?.jsonPrimitive?.content
                ?: throw Exception("price field missing in Binance response")
            return priceStr.toDoubleOrNull()
                ?: throw Exception("price field is not a number: $priceStr")
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun cachePrice(price: Double) {
        prefs.edit()
            .putFloat(KEY_PRICE, price.toFloat())
            .putLong(KEY_PRICE_AT, System.currentTimeMillis())
            .apply()
    }

    private fun cachedPriceIfFresh(): Double? {
        val storedAt = prefs.getLong(KEY_PRICE_AT, 0L)
        if (storedAt == 0L) return null
        val ageMs = System.currentTimeMillis() - storedAt
        if (ageMs > MAX_CACHE_AGE_MS) return null
        val price = prefs.getFloat(KEY_PRICE, -1f)
        return if (price > 0f) price.toDouble() else null
    }

    companion object {
        private const val TAG = "PriceRepository"
        private const val PREFS_NAME = "price_cache"
        private const val KEY_PRICE = "ckb_usd_price"
        private const val KEY_PRICE_AT = "ckb_usd_price_fetched_at"
        private const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L  // 24h
    }
}
