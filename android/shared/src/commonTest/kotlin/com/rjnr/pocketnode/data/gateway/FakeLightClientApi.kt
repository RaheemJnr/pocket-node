package com.rjnr.pocketnode.data.gateway

/**
 * Scripted [LightClientApi] for tests.
 *
 * Answers are queued per function name: the first call to a function takes the
 * head of its queue, and the queue is not consumed once it holds a single
 * entry, so `enqueue("getTipHeader", json)` is enough for a test that polls.
 * An empty queue answers `null` (or the failure value for the non-nullable
 * functions), which is exactly what the real bridge does when the node is not
 * running, so "forgot to script it" and "node is down" exercise the same path.
 *
 * Every call is recorded in [calls] with its arguments, so a test can assert
 * the engine paged correctly or passed the search key it meant to.
 *
 * Open so later M3 suites can override a single function instead of scripting
 * it (the sync engine wants a getCells that pages from a live list); [next] and
 * [nextFlag] are protected so an override can still record its call.
 *
 * Not thread-safe: the call log and the answer queues are plain mutable
 * collections. A test that drives a concurrent engine has to serialise its
 * calls (or wrap this) rather than share one instance across threads.
 */
open class FakeLightClientApi : LightClientApi {

    /** One recorded call: the function name and its arguments in declaration order. */
    data class Call(val function: String, val args: List<Any?>)

    val calls: MutableList<Call> = mutableListOf()

    private val answers: MutableMap<String, MutableList<String?>> = mutableMapOf()
    private val flags: MutableMap<String, MutableList<Boolean>> = mutableMapOf()

    /** Status reported by [status]; also drives [isInitialized] unless overridden. */
    var statusCode: Int = 0

    /** Answer for [isInitialized]. */
    var initialized: Boolean = false

    /** Answer for [calculateMaxWithdraw]; -1 is the bridge's failure sentinel. */
    var maxWithdraw: Long = -1L

    /** The listener handed to [init], so a test can drive status transitions. */
    var listener: LightClientStatusListener? = null
        private set

    /** Queue a String answer for [function]. */
    fun enqueue(function: String, vararg values: String?): FakeLightClientApi {
        answers.getOrPut(function) { mutableListOf() }.addAll(values)
        return this
    }

    /** Queue a Boolean answer for one of the lifecycle or [setScripts] functions. */
    fun enqueueFlag(function: String, vararg values: Boolean): FakeLightClientApi {
        flags.getOrPut(function) { mutableListOf() }.addAll(values.toList())
        return this
    }

    /** All recorded calls to [function], oldest first. */
    fun callsTo(function: String): List<Call> = calls.filter { it.function == function }

    /** Returns the fake to its freshly constructed state, scripted answers included. */
    fun reset() {
        calls.clear()
        answers.clear()
        flags.clear()
        statusCode = 0
        initialized = false
        maxWithdraw = -1L
        listener = null
    }

    protected fun next(function: String, vararg args: Any?): String? {
        calls += Call(function, args.toList())
        val queue = answers[function] ?: return null
        return when (queue.size) {
            0 -> null
            1 -> queue[0]
            else -> queue.removeAt(0)
        }
    }

    protected fun nextFlag(function: String, vararg args: Any?): Boolean {
        calls += Call(function, args.toList())
        val queue = flags[function] ?: return false
        return when (queue.size) {
            0 -> false
            1 -> queue[0]
            else -> queue.removeAt(0)
        }
    }

    // ---- Lifecycle ----

    override fun init(
        configPath: String,
        dataDir: String,
        listener: LightClientStatusListener?
    ): Boolean {
        this.listener = listener
        return nextFlag("init", configPath, dataDir, listener)
    }

    override fun start(): Boolean = nextFlag("start")

    override fun stop(): Boolean = nextFlag("stop")

    override fun status(): Int {
        calls += Call("status", emptyList())
        return statusCode
    }

    override fun isInitialized(): Boolean {
        calls += Call("isInitialized", emptyList())
        return initialized
    }

    // ---- Chain queries ----

    override fun getTipHeader(): String? = next("getTipHeader")

    override fun getGenesisBlock(): String? = next("getGenesisBlock")

    override fun getHeader(hash: String): String? = next("getHeader", hash)

    override fun fetchHeader(hash: String): String? = next("fetchHeader", hash)

    override fun getHeaderByNumber(blockNumber: String): String? =
        next("getHeaderByNumber", blockNumber)

    // ---- Filter scripts ----

    override fun setScripts(scriptsJson: String, command: Int): Boolean =
        nextFlag("setScripts", scriptsJson, command)

    override fun getScripts(): String? = next("getScripts")

    // ---- Indexer queries ----

    override fun getCells(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String? = next("getCells", searchKeyJson, order, limit, cursor)

    override fun getTransactions(
        searchKeyJson: String,
        order: String,
        limit: Int,
        cursor: String?
    ): String? = next("getTransactions", searchKeyJson, order, limit, cursor)

    override fun getCellsCapacity(searchKeyJson: String): String? =
        next("getCellsCapacity", searchKeyJson)

    // ---- Transactions ----

    override fun sendTransaction(txJson: String): String? = next("sendTransaction", txJson)

    override fun getTransaction(hash: String): String? = next("getTransaction", hash)

    override fun fetchTransaction(hash: String): String? = next("fetchTransaction", hash)

    override fun estimateCycles(txJson: String): String? = next("estimateCycles", txJson)

    // ---- Node info ----

    override fun localNodeInfo(): String? = next("localNodeInfo")

    override fun getPeers(): String? = next("getPeers")

    override fun callRpc(method: String): String? = next("callRpc", method)

    // ---- DAO helpers ----

    override fun extractDaoFields(daoHex: String): String? = next("extractDaoFields", daoHex)

    override fun calculateMaxWithdraw(
        depositHeaderDaoHex: String,
        withdrawHeaderDaoHex: String,
        depositCapacity: Long,
        occupiedCapacity: Long
    ): Long {
        calls += Call(
            "calculateMaxWithdraw",
            listOf(
                depositHeaderDaoHex,
                withdrawHeaderDaoHex,
                depositCapacity,
                occupiedCapacity
            )
        )
        return maxWithdraw
    }

    override fun calculateUnlockEpoch(
        depositEpochHex: String,
        withdrawEpochHex: String
    ): String? = next("calculateUnlockEpoch", depositEpochHex, withdrawEpochHex)
}
