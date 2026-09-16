package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The raw JNI surface, one function per `external fun` on [LightClientNative],
 * same names and same signatures.
 *
 * It exists for one reason: `external` methods have no bytecode, so MockK
 * cannot intercept them and a JVM unit test that touches [LightClientNative]
 * dies with `UnsatisfiedLinkError`. [SyncCoordinator] used to carry a
 * three-method version of this seam of its own; this is the same idea widened
 * to the whole bridge so [AndroidLightClientApi] can be tested.
 *
 * Nothing but [AndroidLightClientApi] should depend on this. Application code
 * takes [LightClientApi].
 */
interface NativeLightClient {
    fun nativeInit(configPath: String, statusCallback: LightClientNative.StatusCallback): Boolean
    fun nativeStart(): Boolean
    fun nativeStop(): Boolean
    fun nativeGetStatus(): Int
    fun nativeGetTipHeader(): String?
    fun nativeGetGenesisBlock(): String?
    fun nativeGetHeader(hash: String): String?
    fun nativeFetchHeader(hash: String): String?
    fun nativeGetHeaderByNumber(blockNumber: String): String?
    fun nativeSetScripts(scriptsJson: String, command: Int): Boolean
    fun nativeGetScripts(): String?
    fun nativeGetCells(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String?
    fun nativeGetTransactions(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String?
    fun nativeGetCellsCapacity(searchKeyJson: String): String?
    fun nativeSendTransaction(txJson: String): String?
    fun nativeGetTransaction(hash: String): String?
    fun nativeFetchTransaction(hash: String): String?
    fun nativeEstimateCycles(txJson: String): String?
    fun nativeLocalNodeInfo(): String?
    fun nativeGetPeers(): String?
    fun callRpc(method: String): String?
    fun nativeExtractDaoFields(daoHex: String): String?
    fun nativeCalculateMaxWithdraw(
        depositHeaderDaoHex: String,
        withdrawHeaderDaoHex: String,
        depositCapacity: Long,
        occupiedCapacity: Long
    ): Long
    fun nativeCalculateUnlockEpoch(depositEpochHex: String, withdrawEpochHex: String): String?
}

/** Production surface: every function delegates straight to the JNI object. */
@Singleton
class JniLightClient @Inject constructor() : NativeLightClient {

    override fun nativeInit(
        configPath: String,
        statusCallback: LightClientNative.StatusCallback
    ): Boolean = LightClientNative.nativeInit(configPath, statusCallback)

    override fun nativeStart(): Boolean = LightClientNative.nativeStart()

    override fun nativeStop(): Boolean = LightClientNative.nativeStop()

    override fun nativeGetStatus(): Int = LightClientNative.nativeGetStatus()

    override fun nativeGetTipHeader(): String? = LightClientNative.nativeGetTipHeader()

    override fun nativeGetGenesisBlock(): String? = LightClientNative.nativeGetGenesisBlock()

    override fun nativeGetHeader(hash: String): String? = LightClientNative.nativeGetHeader(hash)

    override fun nativeFetchHeader(hash: String): String? =
        LightClientNative.nativeFetchHeader(hash)

    override fun nativeGetHeaderByNumber(blockNumber: String): String? =
        LightClientNative.nativeGetHeaderByNumber(blockNumber)

    override fun nativeSetScripts(scriptsJson: String, command: Int): Boolean =
        LightClientNative.nativeSetScripts(scriptsJson, command)

    override fun nativeGetScripts(): String? = LightClientNative.nativeGetScripts()

    override fun nativeGetCells(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String? = LightClientNative.nativeGetCells(searchKeyJson, order, limit, cursor)

    override fun nativeGetTransactions(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String? = LightClientNative.nativeGetTransactions(searchKeyJson, order, limit, cursor)

    override fun nativeGetCellsCapacity(searchKeyJson: String): String? =
        LightClientNative.nativeGetCellsCapacity(searchKeyJson)

    override fun nativeSendTransaction(txJson: String): String? =
        LightClientNative.nativeSendTransaction(txJson)

    override fun nativeGetTransaction(hash: String): String? =
        LightClientNative.nativeGetTransaction(hash)

    override fun nativeFetchTransaction(hash: String): String? =
        LightClientNative.nativeFetchTransaction(hash)

    override fun nativeEstimateCycles(txJson: String): String? =
        LightClientNative.nativeEstimateCycles(txJson)

    override fun nativeLocalNodeInfo(): String? = LightClientNative.nativeLocalNodeInfo()

    override fun nativeGetPeers(): String? = LightClientNative.nativeGetPeers()

    override fun callRpc(method: String): String? = LightClientNative.callRpc(method)

    override fun nativeExtractDaoFields(daoHex: String): String? =
        LightClientNative.nativeExtractDaoFields(daoHex)

    override fun nativeCalculateMaxWithdraw(
        depositHeaderDaoHex: String,
        withdrawHeaderDaoHex: String,
        depositCapacity: Long,
        occupiedCapacity: Long
    ): Long = LightClientNative.nativeCalculateMaxWithdraw(
        depositHeaderDaoHex,
        withdrawHeaderDaoHex,
        depositCapacity,
        occupiedCapacity
    )

    override fun nativeCalculateUnlockEpoch(
        depositEpochHex: String,
        withdrawEpochHex: String
    ): String? = LightClientNative.nativeCalculateUnlockEpoch(depositEpochHex, withdrawEpochHex)
}
