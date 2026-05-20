package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.data.gateway.models.EpochInfo
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only JNI passthroughs (#106 phase 4).
 *
 * The NodeStatus / diagnostic surfaces and the DAO + sync paths all
 * read a small set of values straight off the light client: peers,
 * tip header, registered scripts, raw RPC, the parsed tip block
 * number, and the current epoch. None of these mutate repository
 * state — they're pure reads.
 *
 * Pulling them out frees GatewayRepository of ~50 lines of
 * boilerplate and gives tests a focused seam: NodeStatusViewModel
 * (and friends) can take this directly instead of the full
 * GatewayRepository to exercise diagnostic flows.
 *
 * All methods are `suspend` and force `Dispatchers.IO` because JNI
 * calls can block the caller's thread; the original code wrapped
 * each call in `withContext(Dispatchers.IO)` for the same reason.
 */
@Singleton
class LightClientReadOnly @Inject constructor(
    private val json: Json,
) {

    suspend fun getPeers(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetPeers()
    }

    suspend fun getTipHeader(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetTipHeader()
    }

    suspend fun getScripts(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetScripts()
    }

    suspend fun callRpc(method: String): String? = withContext(Dispatchers.IO) {
        LightClientNative.callRpc(method)
    }

    /**
     * Parsed tip block number, or 0L if the light client hasn't
     * reported a header yet (cold start) or the header failed to
     * decode (transient JNI error).
     */
    suspend fun currentTipNumberOrZero(): Long = withContext(Dispatchers.IO) {
        try {
            val tipStr = LightClientNative.nativeGetTipHeader() ?: return@withContext 0L
            val tip = json.decodeFromString<JniHeaderView>(tipStr)
            tip.number.removePrefix("0x").toLong(16)
        } catch (e: Exception) {
            Log.w(TAG, "currentTipNumberOrZero failed: ${e.message}")
            0L
        }
    }

    /**
     * Tip epoch (epoch index + per-epoch fraction). Wrapped in
     * `Result` because DAO flows want explicit failure rather than a
     * sentinel value — without a tip header we can't compute
     * `lockRemainingHours` accurately.
     */
    suspend fun getCurrentEpoch(): Result<EpochInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val headerJson = LightClientNative.nativeGetTipHeader()
                ?: throw Exception("Failed to get tip header")
            val header = json.decodeFromString<JniHeaderView>(headerJson)
            EpochInfo.fromHex(header.epoch)
        }
    }

    companion object {
        private const val TAG = "LightClientReadOnly"
    }
}
