package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding of [LightClientApi] to the JNI bridge.
 *
 * Every function is a one-line passthrough, on purpose: the JNI bridge already
 * answers `null` / `false` / `-1` on failure, which is the error contract
 * [LightClientApi] documents, so there is nothing to translate and Android
 * behaviour is byte-identical to calling `LightClientNative` directly.
 *
 * The calls go through [NativeLightClient] rather than naming
 * `LightClientNative` here, because `external` methods cannot be intercepted on
 * the JVM and the adapter would otherwise be untestable. [JniLightClient] is
 * the production implementation and does nothing but forward.
 *
 * Blocking, like the interface. Callers keep their own
 * `withContext(Dispatchers.IO)` wrapping.
 */
@Singleton
class AndroidLightClientApi @Inject constructor(
    private val native: NativeLightClient,
) : LightClientApi {

    /**
     * Set once [init] succeeds. The JNI bridge exports no `is_initialized`
     * counterpart to the UniFFI one, and `nativeGetStatus` cannot tell "not
     * initialized" from "initialized, waiting for start" because both are 0.
     *
     * Written from whichever thread ran init and read from the pollers, hence
     * `@Volatile`.
     */
    @Volatile
    private var initCompleted = false

    // ---- Lifecycle ----

    /**
     * [dataDir] is ignored. `nativeInit` takes only the config path, and
     * Android writes its TOML into `filesDir` at runtime with the store and
     * network paths already pointing at `data/<network>/`. The parameter exists
     * for iOS, whose container path changes between installs.
     */
    override fun init(
        configPath: String,
        dataDir: String,
        listener: LightClientStatusListener?
    ): Boolean {
        val callback = object : LightClientNative.StatusCallback {
            override fun onStatusChange(status: String, data: String) {
                listener?.onStatusChanged(status, data)
            }
        }
        val ok = native.nativeInit(configPath, callback)
        if (ok) initCompleted = true
        return ok
    }

    override fun start(): Boolean = native.nativeStart()

    override fun stop(): Boolean = native.nativeStop()

    override fun status(): Int = native.nativeGetStatus()

    override fun isInitialized(): Boolean =
        initCompleted || native.nativeGetStatus() != LightClientNative.STATUS_INIT

    // ---- Chain queries ----

    override fun getTipHeader(): String? = native.nativeGetTipHeader()

    override fun getGenesisBlock(): String? = native.nativeGetGenesisBlock()

    override fun getHeader(hash: String): String? = native.nativeGetHeader(hash)

    override fun fetchHeader(hash: String): String? = native.nativeFetchHeader(hash)

    override fun getHeaderByNumber(blockNumber: String): String? =
        native.nativeGetHeaderByNumber(blockNumber)

    // ---- Filter scripts ----

    override fun setScripts(scriptsJson: String, command: Int): Boolean =
        native.nativeSetScripts(scriptsJson, command)

    override fun getScripts(): String? = native.nativeGetScripts()

    // ---- Indexer queries ----

    override fun getCells(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String? = native.nativeGetCells(searchKeyJson, order, limit, cursor)

    override fun getTransactions(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String? = native.nativeGetTransactions(searchKeyJson, order, limit, cursor)

    override fun getCellsCapacity(searchKeyJson: String): String? =
        native.nativeGetCellsCapacity(searchKeyJson)

    // ---- Transactions ----

    override fun sendTransaction(txJson: String): String? = native.nativeSendTransaction(txJson)

    override fun getTransaction(hash: String): String? = native.nativeGetTransaction(hash)

    override fun fetchTransaction(hash: String): String? = native.nativeFetchTransaction(hash)

    override fun estimateCycles(txJson: String): String? = native.nativeEstimateCycles(txJson)

    // ---- Node info ----

    override fun localNodeInfo(): String? = native.nativeLocalNodeInfo()

    override fun getPeers(): String? = native.nativeGetPeers()

    override fun callRpc(method: String): String? = native.callRpc(method)

    // ---- DAO helpers ----

    override fun extractDaoFields(daoHex: String): String? = native.nativeExtractDaoFields(daoHex)

    override fun calculateMaxWithdraw(
        depositHeaderDaoHex: String,
        withdrawHeaderDaoHex: String,
        depositCapacity: Long,
        occupiedCapacity: Long
    ): Long = native.nativeCalculateMaxWithdraw(
        depositHeaderDaoHex,
        withdrawHeaderDaoHex,
        depositCapacity,
        occupiedCapacity
    )

    override fun calculateUnlockEpoch(
        depositEpochHex: String,
        withdrawEpochHex: String
    ): String? = native.nativeCalculateUnlockEpoch(depositEpochHex, withdrawEpochHex)
}
