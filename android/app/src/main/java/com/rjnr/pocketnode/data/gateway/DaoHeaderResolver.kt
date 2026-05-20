package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.data.gateway.models.JniFetchHeaderResponse
import com.rjnr.pocketnode.data.gateway.models.JniFetchTransactionResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniTransactionWithStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chain-state lookups the Nervos DAO flows need (#106 phase 2).
 *
 * The DAO deposit/withdraw/unlock paths in [GatewayRepository] depend on
 * resolving block hashes for DAO cells and fetching the block headers
 * those cells were committed in. None of that requires light-client
 * registration state — it's all read-only JNI + cache plumbing — so it
 * lives in its own @Singleton with three responsibilities:
 *
 *  1. `getBlockHashForCell(txHash)` — light client returns null
 *     `block_hash` on transactions it hasn't fully indexed yet; we
 *     escalate to `nativeFetchTransaction` (peer fetch) with retries.
 *  2. `fetchHeaderWithRetry(blockHash)` — direct peer-fetch with retry
 *     loop, used when the local light client doesn't have a header for
 *     an older block.
 *  3. `getOrFetchHeader(blockHash, network)` — three-tier read: Room
 *     cache → local JNI → peer fetch. Caches positive results in Room
 *     because headers are immutable.
 *
 * The retry loops are tuned for the light-client's `fetching`/`added`
 * intermediate states (peer request enqueued but not yet returned);
 * three attempts with 2-second delays is the same tuning the original
 * GatewayRepository code used.
 */
@Singleton
class DaoHeaderResolver @Inject constructor(
    private val json: Json,
    private val daoSyncManager: DaoSyncManager,
) {

    /**
     * Resolve the block hash for the transaction that produced [txHash].
     *
     * Tries the local light-client cache first (`nativeGetTransaction`),
     * then falls back to a peer-driven fetch with retries. Returns null
     * if the transaction is unknown to the network or the peer fetch
     * keeps returning `fetching` after three retries.
     */
    suspend fun getBlockHashForCell(txHash: String): String? {
        // Try local cache first
        val txJson = LightClientNative.nativeGetTransaction(txHash)
        if (txJson != null) {
            val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(txJson)
            if (txWithStatus.txStatus.blockHash != null) {
                return txWithStatus.txStatus.blockHash
            }
            Log.d(TAG, "  get_transaction found tx but no block_hash, trying fetch_transaction...")
        } else {
            Log.d(TAG, "  get_transaction returned null, trying fetch_transaction...")
        }

        // Fallback: fetch from peers (may need retries as it's async)
        for (attempt in 1..3) {
            val fetchJson = LightClientNative.nativeFetchTransaction(txHash)
            if (fetchJson == null) {
                Log.w(TAG, "  fetch_transaction returned null on attempt $attempt")
                break
            }
            val fetchResp = json.decodeFromString<JniFetchTransactionResponse>(fetchJson)
            Log.d(TAG, "  fetch_transaction attempt $attempt: status=${fetchResp.status}")
            if (fetchResp.status == "fetched" && fetchResp.data != null) {
                return fetchResp.data.txStatus.blockHash
            }
            // "fetching" or "added" — wait and retry
            if (fetchResp.status == "fetching" || fetchResp.status == "added") {
                delay(2000)
            } else {
                break // unknown status, don't retry
            }
        }
        return null
    }

    /**
     * Fetch a block header from peers via `nativeFetchHeader` with
     * retries. The light client only stores headers for blocks it has
     * processed locally; older blocks come from the peer network.
     */
    suspend fun fetchHeaderWithRetry(blockHash: String): JniHeaderView? {
        for (attempt in 1..3) {
            val fetchJson = LightClientNative.nativeFetchHeader(blockHash)
            if (fetchJson == null) {
                Log.w(TAG, "  fetch_header returned null on attempt $attempt")
                break
            }
            val fetchResp = json.decodeFromString<JniFetchHeaderResponse>(fetchJson)
            Log.d(TAG, "  fetch_header attempt $attempt: status=${fetchResp.status}")
            if (fetchResp.status == "fetched" && fetchResp.data != null) {
                return fetchResp.data
            }
            if (fetchResp.status == "fetching" || fetchResp.status == "added") {
                delay(2000)
            } else {
                break
            }
        }
        return null
    }

    /**
     * Cache-first header lookup: Room → local JNI → peer fetch.
     * Block headers are immutable, so cached results are always valid.
     *
     * Positive results from the local JNI path and peer fetch are
     * persisted into the Room header cache so subsequent DAO ops on
     * the same block skip both JNI and network round-trips.
     */
    suspend fun getOrFetchHeader(blockHash: String, network: NetworkType): JniHeaderView? {
        // 1. Check Room cache
        val cached = daoSyncManager.getCachedHeader(blockHash)
        if (cached != null) {
            return cached.toJniHeaderView()
        }

        // 2. Try local JNI (light client may have it in memory)
        val localJson = LightClientNative.nativeGetHeader(blockHash)
        if (localJson != null) {
            val header = json.decodeFromString<JniHeaderView>(localJson)
            daoSyncManager.cacheHeader(header, network.name)
            return header
        }

        // 3. Fetch from peers
        val fetched = fetchHeaderWithRetry(blockHash)
        if (fetched != null) {
            daoSyncManager.cacheHeader(fetched, network.name)
        }
        return fetched
    }

    companion object {
        private const val TAG = "DaoHeaderResolver"
    }
}
