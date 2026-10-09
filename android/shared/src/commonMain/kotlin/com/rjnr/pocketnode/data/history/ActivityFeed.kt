package com.rjnr.pocketnode.data.history

import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.storage.BalanceCache
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import com.rjnr.pocketnode.data.storage.RoomKmpPendingBroadcastStore
import com.rjnr.pocketnode.data.storage.RoomKmpTransactionStore
import com.rjnr.pocketnode.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The activity list's and the balance's read path, for one wallet.
 *
 * The iOS half of what `GatewayRepository` does around `LedgerReader`: it runs
 * the history walk, lets the transaction store cache the result, pages the
 * cache back out, joins each row against its broadcast row, and runs the
 * balance read with the rescue-rescan wiring the repository supplies on
 * Android.
 *
 * It holds no UI strings and makes no display decisions beyond the ones in
 * `ActivityDisplay.kt`, which are pure functions so they can be tested once
 * here rather than again in Swift.
 *
 * ## Why the reads and the list are separate
 *
 * [refresh] walks every page the light client has and writes the whole history
 * into the store; [page] reads back from the store. That split is Android's and
 * it is what makes the list's "All" tab actually mean all: a wallet with more
 * history than one page would otherwise only ever show its newest page.
 */
class ActivityFeed(
    private val ledger: LedgerReader,
    private val transactions: RoomKmpTransactionStore,
    private val pendingBroadcasts: RoomKmpPendingBroadcastStore,
    private val balanceCache: BalanceCache,
    private val coordinator: SyncCoordinator,
    private val engine: SyncEngine,
    private val uiPreferences: UiPreferences,
    private val clock: Clock = SystemClock,
    private val scopeContext: CoroutineContext = Dispatchers.Default,
) {

    private val scope = CoroutineScope(SupervisorJob() + scopeContext)

    private val _broadcasts = MutableStateFlow<List<PendingBroadcastRecord>>(emptyList())

    /**
     * Every broadcast row for the wallet [observeBroadcasts] was last pointed
     * at, terminal ones included.
     *
     * Room-backed and reactive, so the watchdog's CAS updates land here with no
     * extra polling and no bridge call, which is what lets a row transition
     * Broadcasting to Pending to Confirmed in place instead of disappearing and
     * coming back as a new row.
     */
    val broadcasts: StateFlow<List<PendingBroadcastRecord>> = _broadcasts.asStateFlow()

    private var broadcastJob: Job? = null

    private val _cachedBalance = MutableStateFlow<BalanceResponse?>(null)

    /**
     * The last balance read out of the cache, published the moment it is read.
     *
     * A flow rather than the `emitCached` callback `GatewayRepository` passes
     * `LedgerReader` directly, because a Kotlin `suspend (T) -> Unit` parameter
     * exports to Swift as a `KotlinSuspendFunction1` protocol and Swift cannot
     * implement a suspend member. The ordering the callback guarantees is
     * preserved: this emits before the cell walk starts, so a caller that
     * mirrors it onto its own state paints a number first and replaces it with
     * the computed one when [refreshBalance] returns.
     *
     * [primeCachedBalance] emits on it too, so a screen can show a number
     * before the node has even started.
     */
    val cachedBalance: StateFlow<BalanceResponse?> = _cachedBalance.asStateFlow()

    /**
     * Wallets that have already spent their one rescue rescan this process.
     *
     * Owned here for the same reason `GatewayRepository` owns it: the decision
     * and the record of it belong together, and [LedgerReader.readBalance]
     * reads and adds to the set it is handed.
     */
    private val rescanAttempted = mutableSetOf<String>()

    /** Point [broadcasts] at a wallet. Replaces any previous subscription. */
    fun observeBroadcasts(walletId: String, network: NetworkType) {
        broadcastJob?.cancel()
        _broadcasts.value = emptyList()
        broadcastJob = scope.launch {
            pendingBroadcasts.observeAll(walletId, network.name).collect { rows ->
                _broadcasts.value = rows
            }
        }
    }

    /** Stop the broadcast subscription and everything else this object started. */
    fun close() {
        broadcastJob?.cancel()
        broadcastJob = null
        scope.cancel()
    }

    /**
     * Walk the wallet's history and cache it.
     *
     * The returned list is deliberately dropped: [LedgerReader.getTransactions]
     * writes the complete walk into the transaction store on the way through
     * and then truncates what it returns to its `limit`, so the store is the
     * complete answer and the return value is not. [page] reads the store.
     */
    @Throws(Throwable::class)
    suspend fun refresh(
        activeScript: Script?,
        walletId: String,
        network: NetworkType,
    ) {
        ledger.getTransactions(
            activeScript = activeScript,
            activeWalletId = walletId,
            network = network,
            limit = HISTORY_PAGE_LIMIT,
            cursor = null,
        ).getOrThrow()
    }

    /**
     * One page of the list, already joined against the broadcast rows.
     *
     * Offset paging rather than a cursor: the sort is
     * [com.rjnr.pocketnode.data.storage.TransactionRoomDao.pageAll]'s, which is
     * total, so a fixed offset addresses the same row on every read as long as
     * nothing was written in between. A [refresh] between two pages can shift
     * rows; the caller reloads from page 0 after one, which is what the Android
     * Paging source does when its store invalidates.
     */
    @Throws(Throwable::class)
    suspend fun page(
        filter: ActivityFilter,
        walletId: String,
        network: NetworkType,
        pageIndex: Int,
    ): List<ActivityItem> {
        val records = transactions.page(
            walletId = walletId,
            network = network.name,
            directions = filter.directions,
            limit = PAGE_SIZE,
            offset = pageIndex * PAGE_SIZE,
        )
        val byHash = _broadcasts.value.associateBy { it.txHash }
        val now = clock.nowMs()
        return records.map { record ->
            val broadcast = byHash[record.txHash]
            val state = displayStateOf(record, broadcast)
            val since = pendingSince(record, broadcast)
            // Badges a batch of a bulk airdrop, marked at send time. Read from
            // the persisted set rather than the row so it survives the
            // pending-to-confirmed and cache-resync transitions.
            val bulk = uiPreferences.isBulkTxHash(record.txHash)
            ActivityItem(
                record = record.copy(isBulk = bulk),
                broadcast = broadcast,
                isBulk = bulk,
                displayState = state,
                pendingSinceMs = since,
                elapsed = if (showsElapsed(state) && since != null) {
                    elapsedBucket(now - since)
                } else {
                    null
                },
                failureReason = if (state == TxDisplayState.FAILED) {
                    failureReasonOf(broadcast)
                } else {
                    null
                },
            )
        }
    }

    /**
     * Spendable balance for the active wallet, cached before it is returned.
     *
     * Every parameter [LedgerReader.readBalance] takes is supplied the way
     * `GatewayRepository.refreshBalance` supplies it:
     *
     *  - `emitCached` publishes on [cachedBalance], so the screen paints a
     *    number before the cell walk finishes. The repository publishes it on
     *    its own `_balance` stream; here it goes on a flow the caller mirrors.
     *  - `isSyncing` is a supplier, not a snapshot. The rescue rescan is
     *    decided after three cursor walks that take seconds on a wallet with
     *    history, and the sync poll flips the flag every 5 to 10 s; a value
     *    captured at call time could be a stale `false` by then and would
     *    rewind the filter scan mid-catch-up.
     *  - `requestPartialRescan` forwards to the coordinator as a PARTIAL set
     *    with `allowRewind = true`, because the rescue rescan IS the intentional
     *    rewind the clamp exists to let through.
     *  - `rescanAttempted` is this object's process-lifetime set.
     *
     * The freshly computed balance is cached here and returned; the repository
     * caches it at its own call site for the same reason.
     */
    @Throws(Throwable::class)
    suspend fun refreshBalance(
        address: String,
        script: Script?,
        network: NetworkType,
        walletId: String,
    ): BalanceResponse {
        val response = ledger.readBalance(
            address = address,
            script = script,
            network = network,
            walletId = walletId,
            isSyncing = { engine.syncProgress.value.isSyncing },
            rescanAttempted = rescanAttempted,
            emitCached = { cached -> _cachedBalance.value = cached },
            requestPartialRescan = { statuses ->
                coordinator.setScriptsAndRecord(
                    statuses,
                    listOf(walletId),
                    SyncCoordinator.CMD_SET_SCRIPTS_PARTIAL,
                    network,
                    allowRewind = true,
                )
            },
        ).getOrThrow()

        balanceCache.cacheBalance(response, network.name, walletId = walletId)
        return response
    }

    /**
     * Read the cached balance for a wallet and publish it on [cachedBalance].
     *
     * What a screen calls before the node is up. Returns the value as well, so
     * a caller that wants it once rather than as a stream need not subscribe.
     */
    @Throws(Throwable::class)
    suspend fun primeCachedBalance(walletId: String, network: NetworkType): BalanceResponse? =
        balanceCache.getCachedBalance(network.name, walletId = walletId)
            ?.also { _cachedBalance.value = it }

    companion object {
        /** Rows per page. The Android activity list's `PagingConfig(pageSize = 20)`. */
        const val PAGE_SIZE = 20

        /**
         * What [LedgerReader.getTransactions] truncates its RETURN value to. It
         * walks and caches every page regardless, so this bounds nothing that
         * matters here; it is the Android call's value so the two behave alike.
         */
        const val HISTORY_PAGE_LIMIT = 50
    }
}
