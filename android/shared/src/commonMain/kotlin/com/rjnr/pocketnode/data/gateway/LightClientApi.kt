package com.rjnr.pocketnode.data.gateway

/**
 * Platform-neutral view of the embedded CKB light client.
 *
 * One function per bridge export, named after the export with the Android
 * `native` prefix dropped. Android binds it to the JNI object
 * `LightClientNative`; iOS binds it to the UniFFI free functions in the
 * `CkbLightClient` Swift package. Everything the shared sync and send code
 * needs from the node goes through here, so the two platforms run the same
 * engine over the same contract.
 *
 * ## Blocking, not suspending
 *
 * Every function is blocking. Swift cannot implement a Kotlin `suspend`
 * function, so the interface cannot declare any, and the bridge calls block
 * anyway (`init` opens the store and brings the P2P service up; `stop` drains
 * the network for up to two seconds). Callers wrap them the way the Android
 * code already wraps `LightClientNative`:
 *
 * ```kotlin
 * withContext(Dispatchers.IO) { api.getTipHeader() }
 * ```
 *
 * On iOS the implementation must not be called from the main actor.
 *
 * ## Error contract
 *
 * Queries return `null` on **any** failure, and never throw, except [callRpc],
 * which reports JSON-RPC errors in-band. That is the historical JNI contract
 * (see `bridge_core/error.rs`: the JNI bridge collapses every `BridgeError`
 * onto `JNI_FALSE` / `null`), and it is the contract the whole Android app is
 * written against, so the Android adapter is a pure passthrough and its
 * behaviour is byte-identical to calling `LightClientNative` directly.
 *
 * iOS gets richer errors from UniFFI and discards them here on purpose: the
 * adapter catches `LightClientError` (`NotInitialized`, `Stopped`, `NotFound`,
 * `Config`, `Storage`, `Network`, `Internal`, `AlreadyInitialized`), logs the
 * reason through `os.Logger`, and returns `null`. A caller that needs the
 * reason has to reach past this interface to the platform bridge; nothing in
 * the shared engine does.
 *
 * Lifecycle functions answer `Boolean` for the same reason, and
 * [calculateMaxWithdraw] answers `-1` because that is the sentinel the JNI DAO
 * bridge already returns on failure.
 *
 * No default methods are declared: Kotlin interface defaults are invisible to
 * Swift, so a Swift implementation would silently fail to satisfy the protocol.
 */
interface LightClientApi {

    // ---- Lifecycle ----

    /**
     * Initialize the node from the TOML config at [configPath].
     *
     * [dataDir], when non-empty, overrides the store and network paths from the
     * config with `<dataDir>/store.db` and `<dataDir>/network`. iOS needs it
     * because its container path changes between installs. Android ignores it:
     * its config is written into `filesDir` at runtime with the data dir
     * already baked in.
     *
     * [listener] receives state transitions; pass `null` for none.
     *
     * Returns false if init fails, including a second init in the same process
     * (the Rust globals live in `OnceLock`s and cannot be reset).
     */
    fun init(configPath: String, dataDir: String, listener: LightClientStatusListener?): Boolean

    /** Transition INIT to RUNNING. False if the node is not initialized or was stopped. */
    fun start(): Boolean

    /**
     * Shut the node down. Terminal for the life of the process: the globals
     * cannot be cleared, so [start] afterwards always fails and the app has to
     * be relaunched (see the network-switch note in CLAUDE.md).
     */
    fun stop(): Boolean

    /**
     * Current state: 0 = INIT, 1 = RUNNING, 2 = STOPPED, matching
     * `nativeGetStatus` and `get_status`.
     *
     * 0 means both "not initialized yet" and "initialized, waiting for
     * [start]". Use [isInitialized] to tell those apart.
     */
    fun status(): Int

    /**
     * Whether [init] has completed successfully.
     *
     * Disambiguates the two meanings of a [status] of 0: not initialized yet,
     * versus initialized and waiting for [start].
     *
     * The UniFFI bridge exports this directly, answering the Rust expression in
     * `bridge_core/types.rs`: `get_state() != STATE_INIT ||
     * STORAGE_WITH_DATA.get().is_some()`, that is, the client has moved past
     * INIT or its storage global has been published.
     *
     * The JNI bridge has no counterpart, so the Android adapter reproduces that
     * expression from what it can see: a flag it sets on a successful [init]
     * (standing in for "storage was published"), OR'd with `status() != 0`. The
     * flag is only accurate because every Android init goes through this
     * interface, `NodeLifecycle` included; a caller that reaches around it to
     * `LightClientNative.nativeInit` would leave the flag false and the two
     * platforms disagreeing.
     */
    fun isInitialized(): Boolean

    // ---- Chain queries ----

    /** Tip header as a JSON `HeaderView`. */
    fun getTipHeader(): String?

    /** Genesis block as a JSON `BlockView`. */
    fun getGenesisBlock(): String?

    /** Header for a block [hash] as a JSON `HeaderView`; null when not stored locally. */
    fun getHeader(hash: String): String?

    /** Fetch status for a header as a JSON `FetchStatus<HeaderView>`; queues the fetch when unknown. */
    fun fetchHeader(hash: String): String?

    /**
     * Header for [blockNumber] (decimal, or `0x`-prefixed hex) as a JSON
     * `HeaderView`. A two-hop lookup, so it only resolves blocks the light
     * client has processed.
     */
    fun getHeaderByNumber(blockNumber: String): String?

    // ---- Filter scripts ----

    /**
     * Replace, merge or delete the filter scripts to sync.
     *
     * [scriptsJson] is a JSON array of `ScriptStatus`. [command] is 0 = all,
     * 1 = partial, 2 = delete.
     */
    fun setScripts(scriptsJson: String, command: Int): Boolean

    /** Registered filter scripts as a JSON array of `ScriptStatus`. */
    fun getScripts(): String?

    // ---- Indexer queries ----

    /**
     * Live cells matching [searchKeyJson] as a JSON `Pagination<Cell>`.
     *
     * [order] is `"asc"` or `"desc"`. [cursor] is the previous page's
     * `last_cursor`, or null for the first page.
     */
    fun getCells(searchKeyJson: String, order: String, limit: Int, cursor: String?): String?

    /** Transactions matching [searchKeyJson] as a JSON `Pagination<Tx>`; same paging rules as [getCells]. */
    fun getTransactions(searchKeyJson: String, order: String, limit: Int, cursor: String?): String?

    /** Summed capacity of the cells matching [searchKeyJson] as a JSON `CellsCapacity`. */
    fun getCellsCapacity(searchKeyJson: String): String?

    // ---- Transactions ----

    /** Verify and broadcast [txJson]; returns the tx hash as a JSON string. */
    fun sendTransaction(txJson: String): String?

    /** Transaction and status for [hash] as a JSON `TransactionWithStatus`. */
    fun getTransaction(hash: String): String?

    /** Fetch status for a transaction as a JSON `FetchStatus<TransactionWithStatus>`. */
    fun fetchTransaction(hash: String): String?

    /** Cycles [txJson] would consume. Not implemented in the bridge yet: always null. */
    fun estimateCycles(txJson: String): String?

    // ---- Node info ----

    /** Local node info as a JSON `LocalNode`. */
    fun localNodeInfo(): String?

    /** Connected peers as a JSON array of `RemoteNode`. */
    fun getPeers(): String?

    /**
     * Read-only node RPC by [method] name, returning a JSON-RPC 2.0 response.
     * Supports `get_peers`, `get_tip_header`, `get_genesis_block`, `get_scripts`.
     *
     * The one exception to this interface's null-on-failure rule: an
     * uninitialized node and an unknown method both come back as a **non-null**
     * JSON-RPC error envelope (`{"jsonrpc":"2.0","id":1,"error":{...}}`, codes
     * -32603 and -32601), because `bridge_core/rpc.rs` reports them inside the
     * response rather than as a transport failure. Only a serialization failure
     * yields null here, so a caller has to look for an `error` member instead
     * of relying on nullness.
     */
    fun callRpc(method: String): String?

    // ---- DAO helpers (pure functions, no node state) ----

    /** Split a 32-byte DAO header field into its C, AR, S and U values, as JSON. */
    fun extractDaoFields(daoHex: String): String?

    /**
     * Max withdrawable capacity in shannons for a DAO deposit.
     *
     * Returns `-1` on failure, the sentinel the JNI DAO bridge already uses
     * (a `jlong` cannot be null).
     */
    fun calculateMaxWithdraw(
        depositHeaderDaoHex: String,
        withdrawHeaderDaoHex: String,
        depositCapacity: Long,
        occupiedCapacity: Long
    ): Long

    /** Since value (absolute epoch, hex) that unlocks a DAO withdrawal's phase 2. */
    fun calculateUnlockEpoch(depositEpochHex: String, withdrawEpochHex: String): String?
}

/**
 * Receives light-client state transitions.
 *
 * Mirror of `LightClientNative.StatusCallback`. [status] is the state name
 * (`"initialized"`, `"running"`, `"stopped"`) and [data] is an extra payload
 * that the bridge currently always leaves empty.
 *
 * Called from a native thread on both platforms, so implementations must be
 * thread-safe and must not block. The UniFFI bridge reports a numeric state
 * instead; the iOS adapter maps it onto the same three names.
 */
interface LightClientStatusListener {
    fun onStatusChanged(status: String, data: String)
}
