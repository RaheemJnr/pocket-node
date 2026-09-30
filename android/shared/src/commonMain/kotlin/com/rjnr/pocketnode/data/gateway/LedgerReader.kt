package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.CellsResponse
import com.rjnr.pocketnode.data.gateway.models.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.JniCell
import com.rjnr.pocketnode.data.gateway.models.JniCellsCapacity
import com.rjnr.pocketnode.data.gateway.models.JniFetchHeaderResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.JniSearchKey
import com.rjnr.pocketnode.data.gateway.models.JniTransactionWithStatus
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionsResponse
import com.rjnr.pocketnode.data.storage.BalanceCache
import com.rjnr.pocketnode.data.storage.HeaderCache
import com.rjnr.pocketnode.data.storage.SubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.TransactionStore
import com.rjnr.pocketnode.data.storage.WalletRegistry
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import com.rjnr.pocketnode.util.redactAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/**
 * The wallet's read path, extracted from `GatewayRepository` into the shared
 * core (M3 #4): spendable balance, live cells, transaction history and the
 * status of a single transaction.
 *
 * Everything here is a READ. Nothing mutates the light client's filter scripts
 * or broadcasts anything, with one deliberate exception: the 0-live-cells
 * rescue rescan (#332), which the caller supplies as [readBalance]'s
 * `requestPartialRescan` lambda so the registration mechanics stay with
 * `SyncCoordinator` and the repository that owns the network and the
 * rewind flag.
 *
 * ## What stayed behind
 *
 * The repository still owns the mutable wallet state these reads depend on:
 * the active wallet's script and id, the current network, the `_balance`
 * stream, and whether the sync poll thinks it is catching up.
 *
 * Wallet identity arrives as a VALUE, so one call reads the same wallet from
 * start to finish rather than re-reading a field a wallet switch could move
 * underneath it. Sync state arrives as a SUPPLIER, because the one decision
 * that consults it is taken at the end of the call and has to see the flag as
 * it is then, not as it was when the walk started. See [readBalance].
 *
 * ## Threading
 *
 * The bridge calls are blocking and are deliberately NOT wrapped in
 * `withContext` here, because the repository code this replaces did not wrap
 * them either: they run on whichever dispatcher the caller is already on.
 * [queryContext] exists only for [lightClientReadyForRead], which used to go
 * through `LightClientReadOnly` and therefore did hop to IO.
 */
class LedgerReader(
    private val lightClient: LightClientApi,
    private val balanceCache: BalanceCache,
    private val transactionStore: TransactionStore,
    private val headerCache: HeaderCache,
    private val walletRegistry: WalletRegistry,
    private val candidates: SubAccountCandidateStore,
    private val syncPreferences: SyncPreferences,
    private val uiPreferences: UiPreferences,
    private val json: Json,
    private val logger: Logger,
    private val queryContext: CoroutineContext = Dispatchers.Default,
) {

    /**
     * Spendable balance for the active wallet.
     *
     * Two pieces of the original `refreshBalance` deliberately stay with the
     * caller, because both write repository state: the cached balance is
     * handed back through [emitCached] for the caller to publish on its
     * `_balance` stream, and the freshly computed one is returned rather than
     * cached here. The caller publishes and caches it, in that order, exactly
     * as before.
     *
     * [script] is nullable so the "No wallet" failure keeps its original
     * position: the caller resolves the address first (and fails with
     * "Wallet not initialized" if it cannot), then this fails on a null script.
     *
     * [rescanAttempted] is the caller's process-lifetime set of wallets that
     * have already spent their one rescue rescan; it is read and added to
     * here so the decision and the record stay together.
     *
     * [isSyncing] is a supplier rather than a value on purpose. The rescue
     * rescan is decided AFTER three cursor walks, which on a long-history
     * wallet take seconds, and the sync poll flips the flag every 5 to 10 s.
     * A value captured at call time could be a stale `false` by then and would
     * rewind the filter scan mid-catch-up.
     */
    suspend fun readBalance(
        address: String,
        script: Script?,
        network: NetworkType,
        walletId: String,
        isSyncing: () -> Boolean,
        rescanAttempted: MutableSet<String>,
        emitCached: suspend (BalanceResponse) -> Unit,
        requestPartialRescan: suspend (List<JniScriptStatus>) -> Unit,
    ): Result<BalanceResponse> = runCatching {
        val myScript = script ?: throw Exception("No wallet")

        // --- Cache-first: emit cached balance immediately ---
        balanceCache.getCachedBalance(network.name, walletId = walletId)?.let {
            emitCached(it)
        }

        val searchKey = JniSearchKey(script = myScript)
        logger.d(TAG, "🔍 Fetching balance for script args ${searchKey.script.args.redactAddress()}")

        val responseJson = lightClient.getCellsCapacity(json.encodeToString(searchKey))
            ?: throw Exception(readPathNullMessage("get balance", lightClientReadyForRead()))

        logger.d(TAG, "📊 Raw capacity response: $responseJson")

        val cap = json.decodeFromString<JniCellsCapacity>(responseJson)

        // Convert to balance response
        var capacityVal = cap.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L

        // The light client's getCellsCapacity may include spent cells
        // We need to calculate the true balance by getting live cells only
        logger.d(TAG, "🔍 Calculating true balance by filtering out spent cells...")

        try {
            // ALL spent outpoints, cursor-walked (see fetchAllSpentOutpoints
            // KDoc — the old single limit=100 page under/over-counted balances
            // for wallets with >100 transactions).
            val spentOutpoints = fetchAllSpentOutpoints(json.encodeToString(searchKey))
            logger.d(TAG, "📋 Found ${spentOutpoints.size} spent outpoints")

            // Walk ALL cells the same way — one page hid everything past the
            // first 100 cells from the balance.
            val allBalanceCells = mutableListOf<JniCell>()
            run {
                var c: String? = null
                var pages = 0
                while (pages < MAX_CELL_PAGES) {
                    val pageJson = lightClient.getCells(
                        json.encodeToString(searchKey), "desc", 100, c
                    ) ?: break
                    val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
                    allBalanceCells.addAll(page.objects)
                    pages++
                    if (page.objects.isEmpty() || page.objects.size < 100 ||
                        page.lastCursor.isNullOrEmpty()
                    ) break
                    c = page.lastCursor
                }
            }

            if (allBalanceCells.isNotEmpty()) {
                var liveCapacity = 0L
                var liveCellCount = 0
                var typedCellCount = 0

                allBalanceCells.forEach { cell ->
                    val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                    if (outpointKey !in spentOutpoints) {
                        // Exclude cells with type scripts (DAO cells, etc.) from available balance
                        // Like Neuron: typeHash IS NULL AND hasData = false
                        // Skip a record with an unparseable capacity rather than
                        // letting one bad value throw and poison the whole balance
                        // page (#321). toLongOrNull also guards u64 > Long.MAX.
                        val cellCapacity = cell.output.capacity.removePrefix("0x").toLongOrNull(16)
                        if (cellCapacity == null) {
                            logger.w(TAG, "Skipping cell $outpointKey with unparseable capacity ${cell.output.capacity}")
                        } else if (cell.output.type != null) {
                            typedCellCount++
                            logger.d(TAG, "🔒 DAO/typed cell excluded from balance: $outpointKey = $cellCapacity shannons")
                        } else {
                            liveCapacity += cellCapacity
                            liveCellCount++
                            logger.d(TAG, "✅ Live cell: $outpointKey = $cellCapacity shannons")
                        }
                    } else {
                        logger.d(TAG, "❌ Spent cell: $outpointKey (filtered out)")
                    }
                }

                logger.d(TAG, "💰 Live balance: $liveCellCount cells, $liveCapacity shannons")
                capacityVal = liveCapacity

                // Rescue rescan (#332): only for a wallet that is GENUINELY
                // empty (no spendable AND no typed/DAO cells) yet has history,
                // at most once per wallet per process, and never while sync is
                // still catching up. The old `liveCapacity == 0` trigger
                // counted DAO deposits as nothing and re-fired after every
                // refresh — rewinding the light client to the wallet's
                // earliest transaction in an infinite loop.
                // Ascending order: the first page holds the OLDEST txs, which
                // is exactly what the earliest-block rewind wants (the old
                // desc-order page could miss the true earliest on >100-tx
                // wallets).
                val ascTxJson = lightClient.getTransactions(
                    json.encodeToString(searchKey), "asc", 100, null
                )
                if (ascTxJson != null) {
                    val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(ascTxJson)
                    if (shouldAttemptZeroCellRescan(
                            spendableCapacity = liveCapacity,
                            typedCellCount = typedCellCount,
                            hasTransactions = txPag.objects.isNotEmpty(),
                            // Persisted across launches (knmo): without this the
                            // in-memory set re-armed every cold start and a
                            // permanently-empty primary rewound on every launch.
                            alreadyAttempted = walletId in rescanAttempted ||
                                syncPreferences.isZeroCellRescanDone(walletId),
                            // Read HERE, not at the top of the call: the spent
                            // walk, the cell walk and the ascending page above
                            // can take many seconds, and the poller flips this
                            // flag every 5 to 10 s. A stale `false` would fire
                            // the rewind mid-catch-up, which is the one thing
                            // this guard exists to prevent (#332).
                            isSyncing = isSyncing(),
                        )
                    ) {
                        rescanAttempted.add(walletId)
                        syncPreferences.setZeroCellRescanDone(walletId)
                        logger.w(TAG, "🔄 Have ${txPag.objects.size} transactions but 0 live cells - triggering rescan")
                        val earliestBlock = txPag.objects
                            .mapNotNull { it.blockNumber.removePrefix("0x").toLongOrNull(16) }
                            .minOrNull() ?: 0L
                        val rescanFrom = (earliestBlock - 100).coerceAtLeast(0L)
                        logger.d(TAG, "🔄 Rescan from block $rescanFrom (earliest tx at $earliestBlock)")

                        val blockNumberHex = "0x${rescanFrom.toString(16)}"
                        val scriptStatuses = listOf(
                            JniScriptStatus(
                                script = myScript,
                                scriptType = "lock",
                                blockNumber = blockNumberHex
                            )
                        )
                        requestPartialRescan(scriptStatuses)
                        // Deliberately NOT persisted via setWalletSyncBlock: the
                        // light client's own storage carries the rewind for this
                        // session, and the sync poll's monotonic write records
                        // progress as it advances. Persisting the regression made
                        // it survive restarts — re-registering from the rewound
                        // block forever (#332).
                        logger.d(TAG, "✅ Rescan triggered (partial) - balance should update on next refresh")
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to calculate live balance: ${e.message}")
            // Fall back to the raw capacity value if filtering fails
        }

        val ckbVal = capacityVal / 100_000_000.0

        logger.d(TAG, "💰 Final balance: $capacityVal shannons = $ckbVal CKB (at block ${cap.blockNumber})")

        BalanceResponse(
            address = address,
            capacity = "0x${capacityVal.toString(16)}",
            capacityCkb = ckbVal.toString(),
            asOfBlock = cap.blockNumber
        )
    }

    /**
     * #435: recompute the spendable balance for a wallet OTHER than the active
     * one, keyed under [walletId], from its [address]. The caller writes the
     * result to the balance cache.
     *
     * The account switcher renders each account's balance from the balance
     * cache. That cache was only written for a wallet while it was active (via
     * [readBalance]), so a confirmed transfer INTO a sub-account left the
     * sub-account's switcher balance stale until the user switched to it. All
     * wallets' lock scripts are registered with the light client
     * (SyncCoordinator), so the cell scan here sees the sub-account's cells
     * even while it is inactive.
     *
     * Unlike [readBalance] this deliberately does NOT touch the active
     * wallet's balance stream and does NOT run the zero-cell rescue rescan
     * (an active-wallet-only recovery that rewinds the script).
     */
    suspend fun readBalanceForWallet(
        walletId: String,
        address: String,
        network: NetworkType,
    ): Result<BalanceResponse> = runCatching {
        val script = AddressUtils.decode(address)
        val searchKey = JniSearchKey(script = script)
        val searchKeyJson = json.encodeToString(searchKey)

        val responseJson = lightClient.getCellsCapacity(searchKeyJson)
            ?: throw Exception(readPathNullMessage("get balance", lightClientReadyForRead()))
        val cap = json.decodeFromString<JniCellsCapacity>(responseJson)
        var capacityVal = cap.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L

        // Same live-cell filtering as readBalance: drop spent + typed cells.
        try {
            val spentOutpoints = fetchAllSpentOutpoints(searchKeyJson)
            val allBalanceCells = mutableListOf<JniCell>()
            var c: String? = null
            var pages = 0
            while (pages < MAX_CELL_PAGES) {
                val pageJson = lightClient.getCells(searchKeyJson, "desc", 100, c) ?: break
                val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
                allBalanceCells.addAll(page.objects)
                pages++
                if (page.objects.isEmpty() || page.objects.size < 100 || page.lastCursor.isNullOrEmpty()) break
                c = page.lastCursor
            }
            if (allBalanceCells.isNotEmpty()) {
                var liveCapacity = 0L
                allBalanceCells.forEach { cell ->
                    val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                    if (outpointKey !in spentOutpoints) {
                        val cellCapacity = cell.output.capacity.removePrefix("0x").toLongOrNull(16)
                        if (cellCapacity != null && cell.output.type == null) liveCapacity += cellCapacity
                    }
                }
                capacityVal = liveCapacity
            }
        } catch (e: Exception) {
            // Wording kept verbatim from the repository method this replaces:
            // it is what a user's logcat report is grepped for.
            logger.w(TAG, "refreshBalanceForWallet($walletId): live filter failed, using raw capacity: ${e.message}")
        }

        BalanceResponse(
            address = address,
            capacity = "0x${capacityVal.toString(16)}",
            capacityCkb = (capacityVal / 100_000_000.0).toString(),
            asOfBlock = cap.blockNumber
        )
    }

    /**
     * Live, spendable cells for [script]: the complete cursor walk minus every
     * spent outpoint and every cell carrying a type script.
     *
     * The caller resolves [script] (from an explicit address on the send path,
     * or from the active wallet) so the snapshot taken under the send mutex
     * stays authoritative even if the active wallet changes mid-call.
     */
    suspend fun getCells(
        script: Script,
        limit: Int = 100,
        cursor: String? = null,
    ): Result<CellsResponse> = runCatching {
        val searchKey = JniSearchKey(script = script)

        logger.d(TAG, "🔍 getCells: Fetching cells for script args ${searchKey.script.args.redactAddress()}")

        // ALL spent outpoints, cursor-walked to the end (see helper KDoc —
        // the old single limit=100 page broke wallets with >100 txs).
        val spentOutpoints = fetchAllSpentOutpoints(json.encodeToString(searchKey))
        logger.d(TAG, "📋 getCells: Found ${spentOutpoints.size} spent outpoints")

        // Walk the cell cursor too: a wallet holding >`limit` cells only ever
        // exposed its first page to coin selection and the balance math.
        val allCells = mutableListOf<JniCell>()
        var cellCursor: String? = cursor
        var lastCursorOut: String? = null
        var cellPages = 0
        while (cellPages < MAX_CELL_PAGES) {
            val pageJson = lightClient.getCells(
                json.encodeToString(searchKey), "desc", limit, cellCursor
            ) ?: if (cellPages == 0) {
                throw Exception(readPathNullMessage("get cells", lightClientReadyForRead()))
            } else break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            allCells.addAll(page.objects)
            lastCursorOut = page.lastCursor
            cellPages++
            if (page.objects.isEmpty() || page.objects.size < limit ||
                page.lastCursor.isNullOrEmpty()
            ) break
            cellCursor = page.lastCursor
        }
        if (cellPages >= MAX_CELL_PAGES) {
            logger.w(TAG, "getCells: hit $MAX_CELL_PAGES-page cap (${allCells.size} cells) — set may be incomplete")
        }

        val liveCells = allCells.filter { cell ->
            val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            val isLive = outpointKey !in spentOutpoints
            if (!isLive) {
                logger.d(TAG, "❌ getCells: Filtering out spent cell: $outpointKey")
            }
            // Also exclude cells with type scripts (DAO cells) — they can't be spent as regular inputs
            val hasTypeScript = cell.output.type != null
            if (hasTypeScript && isLive) {
                logger.d(TAG, "🔒 getCells: Excluding typed cell (DAO): $outpointKey")
            }
            isLive && !hasTypeScript
        }.map { it.toCell() }

        logger.d(TAG, "✅ getCells: ${liveCells.size} live cells (filtered from ${allCells.size} total)")

        CellsResponse(liveCells, lastCursorOut)
    }

    /**
     * ALL spent outpoints for a script, walking the transaction cursor to the
     * end. The previous single limit=100 page silently truncated the spent
     * set for wallets with >100 transactions: cell selection then picked
     * already-spent cells, every send failed local verification with a
     * "network rejected" error that survived reinstall (it re-derives from
     * the same chain data), and the balance math subtracted the wrong cells
     * (Alex, Telegram 2026-07, ~646k CKB of history). Page cap is a runaway
     * guard, far above real usage; truncation past it is logged, never silent.
     */
    suspend fun fetchAllSpentOutpoints(searchKeyJson: String): MutableSet<String> {
        // Walk every page THEN collect — see TransactionWalk.kt. Single-page
        // reads here are the #386/#388 bug (yanli's 100-item boundary).
        val walk = walkAllPages(pageLimit = 100, maxPages = MAX_TX_PAGES) { cursor ->
            lightClient.getTransactions(searchKeyJson, "desc", 100, cursor)
                ?.let { json.decodeFromString<JniPagination<JniTxWithCell>>(it) }
        }
        if (walk.hitCap) {
            logger.w(TAG, "fetchAllSpentOutpoints: hit $MAX_TX_PAGES-page cap — spent set may be incomplete")
        }
        return spentOutpointsOf(walk.items).toMutableSet()
    }

    /**
     * Readiness probe for the read-path null classifier ([readPathNullMessage]).
     * A tip header means the light client is up and reporting chain state; its
     * absence means cold start / still starting up. Only ever called on an
     * already-failed read, so the extra bridge hop is off the hot path. Any
     * exception here is treated as "not ready" so we never upgrade a real
     * outage into a misleading transient message.
     *
     * The one read here that hops to [queryContext]: it used to go through
     * `LightClientReadOnly`, which forces `Dispatchers.IO`.
     */
    private suspend fun lightClientReadyForRead(): Boolean =
        runCatching { withContext(queryContext) { lightClient.getTipHeader() } != null }
            .getOrDefault(false)

    /**
     * The lock-script args a self-transfer check should treat as "ours" for
     * [walletId]: its own main script ([mainScriptArgs]) plus its own
     * non-RESTORED sub-account candidates (see
     * [activeSelfTransferCandidateArgs]), minus every OTHER wallet's own
     * address on [network] (defence in depth: a send from wallet A to wallet
     * B is a real transfer, not a self transfer, even though the broader #382
     * gap-limit `knownLockArgs` set intentionally spans every wallet). Shared
     * by [getTransactions] (the confirmed row) and
     * `SendPipeline.buildReserveAndSend` (the pending row inserted at send
     * time, public #538 review) so both classify a self-send to a derived
     * candidate address the same way.
     */
    suspend fun selfWalletLockArgsFor(
        mainScriptArgs: String,
        walletId: String,
        network: NetworkType,
    ): Set<String> {
        val otherWalletLockArgs: Set<String> = try {
            buildSet {
                val addressPicker: (com.rjnr.pocketnode.data.storage.WalletRecord) -> String =
                    if (network == NetworkType.MAINNET) { w -> w.mainnetAddress } else { w -> w.testnetAddress }
                walletRegistry.allWallets().forEach { w ->
                    if (w.walletId == walletId) return@forEach
                    val addr = addressPicker(w)
                    if (addr.isNotBlank()) {
                        runCatching { WalletDerivation.lockScriptFromAddress(addr) }
                            .getOrNull()?.let { add(it.args.lowercase()) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "selfWalletLockArgsFor: other-wallets script set incomplete: ${e.message}")
            emptySet()
        }
        return try {
            val selfArgs = buildSet {
                add(mainScriptArgs.lowercase())
                activeSelfTransferCandidateArgs(candidates.getForParent(walletId))
                    .forEach { add(it.lowercase()) }
            }
            // Both sides lowercased before the subtraction (#538 review): a
            // case mismatch between how a script's args were normalized here
            // vs. in the other-wallets loop above must never let a restored
            // child's (or any other wallet's) script survive into the result.
            selfArgs - otherWalletLockArgs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "selfWalletLockArgsFor: self-transfer script set incomplete: ${e.message}")
            setOf(mainScriptArgs.lowercase())
        }
    }

    /**
     * Transaction history for the active wallet: the complete interaction
     * walk, grouped and netted per transaction, cached, then merged with the
     * wallet's local pending rows.
     *
     * [cursor] is accepted and ignored, as it always has been: the walk leaves
     * nothing to continue from and the returned cursor is always null.
     */
    suspend fun getTransactions(
        activeScript: Script?,
        activeWalletId: String,
        network: NetworkType,
        limit: Int = 50,
        cursor: String? = null,
        /**
         * The fee a gap-limit sweep recorded for a transaction hash at send
         * time, or null (public #538). Android keeps that marker in its own
         * WalletPreferences, outside the shared preference contracts, because
         * the gap-limit sweep does not exist on iOS; iOS leaves the default.
         */
        sweepFeeShannons: (txHash: String) -> Long? = { null },
    ): Result<TransactionsResponse> = runCatching {
        val myScript = activeScript ?: throw Exception("No wallet")
        val searchKey = JniSearchKey(script = myScript)

        // Walk ALL interaction pages before grouping (same class of fix as
        // the #386 spent-set walk). Two bugs lived in the old single
        // limit-sized page:
        //  1. The Room cache below — which feeds the Activity tab's Paging
        //     source — only ever saw the newest page, so wallets with more
        //     history silently lost their older transactions from the list.
        //  2. `limit` counts CELL INTERACTIONS, not transactions, and the
        //     per-tx net amount is computed by grouping interactions. A tx
        //     straddling the page boundary was computed from PART of its
        //     interactions — wrong amount/direction written to the cache.
        // Walking to the end fixes both; grouping happens once over the
        // complete set. The page cap is a runaway guard, logged when hit.
        // Walk every page THEN group/net — see TransactionWalk.kt. pagesWalked==0
        // means the very first bridge read failed (an empty history still returns
        // one empty page, so pagesWalked==1); preserve the original throw so a
        // cold-start failure isn't mistaken for a wallet with no transactions.
        val searchKeyJson = json.encodeToString(searchKey)
        val walk = walkAllPages(pageLimit = 100, maxPages = MAX_TX_PAGES) { c ->
            lightClient.getTransactions(searchKeyJson, "desc", 100, c)
                ?.let { json.decodeFromString<JniPagination<JniTxWithCell>>(it) }
        }
        if (walk.pagesWalked == 0) throw Exception("Failed to get transactions")
        if (walk.hitCap) {
            logger.w(TAG, "getTransactions: hit $MAX_TX_PAGES-page cap (${walk.items.size} interactions) — history may be incomplete")
        }
        val allInteractions = walk.items
        logger.d(TAG, "📡 getTransactions: ${allInteractions.size} interactions walked")

        // Net per transaction, computed once over the COMPLETE walk (the #388
        // fix: a boundary-straddling tx must not be scored from a partial page).
        val netByTx = netShannonsByTx(allInteractions)

        // Group by transaction hash to show a clean "one entry per transaction" UI
        val groupedTransactions = allInteractions.groupBy { it.transaction.hash }

        // #382 gap-limit signature: every lock-script args we track — all
        // wallets (both networks share args; the address differs, the script
        // args don't) plus every sub-account candidate. Failure here must
        // never break the transaction list; an incomplete set only means the
        // banner may not arm this pass.
        val knownLockArgs: Set<String> = runCatching {
            buildSet {
                add(myScript.args)
                val addressPicker: (com.rjnr.pocketnode.data.storage.WalletRecord) -> String =
                    if (network == NetworkType.MAINNET) { w -> w.mainnetAddress } else { w -> w.testnetAddress }
                walletRegistry.allWallets().forEach { w ->
                    val addr = addressPicker(w)
                    if (addr.isNotBlank()) {
                        runCatching { WalletDerivation.lockScriptFromAddress(addr) }
                            .getOrNull()?.let { add(it.args) }
                    }
                }
                addAll(candidates.allScriptArgs())
            }
        }.getOrElse {
            logger.w(TAG, "getTransactions: known-scripts set incomplete: ${it.message}")
            setOf(myScript.args)
        }

        // Self-transfer scope, narrower than knownLockArgs above: see
        // selfWalletLockArgsFor's KDoc. Shared with buildReserveAndSend so a
        // freshly-sent self-transfer's pending row classifies the same way
        // the confirmed row eventually will (#538 review).
        val selfWalletLockArgs: Set<String> = selfWalletLockArgsFor(myScript.args, activeWalletId, network)
        var gapLimitSignal = false

        // Fetch tip height once for confirmation calculations (avoid per-tx bridge calls)
        val tipHeight = runCatching {
            lightClient.getTipHeader()
                ?.let { json.decodeFromString<JniHeaderView>(it) }
                ?.number?.removePrefix("0x")?.toLongOrNull(16)
        }.getOrNull() ?: 0L

        val items = groupedTransactions.map { (txHash, cellInteractions) ->
            val firstInteraction = cellInteractions.first()
            val tx = firstInteraction.transaction

            // Net balance change = Sum(Outputs to us) - Sum(Inputs from us),
            // taken from the precomputed complete-walk map (netShannonsByTx).
            val netChangeShannons = netByTx[txHash] ?: 0L

            // A real self transfer always pays the fee, so its net change is
            // negative, not zero: "out" unless every output lands on this
            // wallet's own script (see isSelfTransferSignature, scoped by
            // selfWalletLockArgs above). DAO deposits and withdrawals also net
            // negative but are reclassified below by finalDirection, which
            // takes priority over this "out"/"self" call.
            val direction = when {
                netChangeShannons > 0 -> "in"
                netChangeShannons < 0 ->
                    if (isSelfTransferSignature(netChangeShannons, tx.outputs, selfWalletLockArgs)) "self" else "out"
                else -> "self"
            }

            // #382: outgoing tx whose change went to no script we know —
            // the seed was likely also used in a standard BIP44 wallet
            // (Neuron) whose change chain we don't derive yet.
            if (!gapLimitSignal &&
                isUnknownChangeSignature(netChangeShannons, tx.outputs, knownLockArgs)
            ) {
                gapLimitSignal = true
                logger.i(TAG, "getTransactions: gap-limit signature in $txHash (#382)")
            }

            // For display, we show the absolute value as the amount
            val amount = if (netChangeShannons < 0) -netChangeShannons else netChangeShannons

            // Attempt to fetch block header to get real timestamp and block hash.
            // For a 50-tx page this used to do 50 get_header round-trips
            // even when the same headers had been resolved seconds earlier.
            // header_cache is consulted first; the bridge is only invoked on miss
            // and the result is persisted so the next page-load is free.
            data class HeaderInfo(val timestampHex: String?, val hash: String?)
            val headerInfo: HeaderInfo = runCatching {
                val txWithStatus = lightClient.getTransaction(txHash)
                    ?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
                val blockHashFromStatus = txWithStatus?.txStatus?.blockHash
                if (blockHashFromStatus != null) {
                    val cached = headerCache.get(blockHashFromStatus)
                    if (cached != null) {
                        HeaderInfo(timestampHex = cached.timestamp, hash = cached.hash)
                    } else {
                        // Try the local bridge lookup first, then trigger a fetch if not cached
                        val headerJson = lightClient.getHeader(blockHashFromStatus)
                        val header = headerJson?.let { json.decodeFromString<JniHeaderView>(it) }
                        if (header != null) {
                            runCatching {
                                headerCache.put(header, network.name)
                            }
                            HeaderInfo(timestampHex = header.timestamp, hash = header.hash)
                        } else {
                            // Header not cached locally — ask light client to fetch it
                            val fetchJson = lightClient.fetchHeader(blockHashFromStatus)
                            val fetchResult = fetchJson?.let { json.decodeFromString<JniFetchHeaderResponse>(it) }
                            val fetchedHeader = fetchResult?.data
                            if (fetchResult?.status == "fetched" && fetchedHeader != null) {
                                runCatching {
                                    headerCache.put(fetchedHeader, network.name)
                                }
                                HeaderInfo(timestampHex = fetchedHeader.timestamp, hash = fetchedHeader.hash)
                            } else {
                                HeaderInfo(null, null)
                            }
                        }
                    }
                } else HeaderInfo(null, null)
            }.onFailure { e ->
                logger.w(TAG, "getTransactions: failed to fetch header for $txHash: ${e.message}")
            }.getOrElse { HeaderInfo(null, null) }

            // Derive confirmations from tip block height vs transaction block number
            val txBlockNum = firstInteraction.blockNumber.removePrefix("0x")
                .toLongOrNull(16) ?: 0L
            val confirmations = if (tipHeight > 0L && txBlockNum > 0L) {
                (tipHeight - txBlockNum).coerceAtLeast(0L).toInt()
            } else {
                0  // unknown = treat as pending
            }

            // Detect DAO operation type from output type scripts and header deps:
            //   Deposit:  DAO output + no header deps
            //   Withdraw: DAO output + 1 header dep (deposit block)
            //   Unlock:   no DAO output + 2 header deps (deposit + withdraw blocks)
            val hasDaoOutput = tx.outputs.any { output ->
                output.type?.codeHash == DaoConstants.DAO_CODE_HASH
            }

            // Network fee (#497): Σ(inputs) − Σ(outputs). The interaction walk
            // already carries a capacity for every input cell the wallet owns,
            // so no second fetch is needed — but it carries NO capacity for a
            // foreign input, which is why the count is checked. An incoming tx
            // resolves none of its inputs and lands on null, and the detail
            // sheet hides the row for it anyway.
            // Parsed strictly (no `?: 0L`): a capacity read as 0 because the
            // node sent something malformed would move the fee by that whole
            // cell. computeFeeShannons poisons the result to null instead.
            val feeShannons = computeFeeShannons(
                resolvedInputs = cellInteractions
                    .filter { it.ioType == "input" }
                    .map { it.ioCapacity.removePrefix("0x").toLongOrNull(16) },
                declaredInputCount = tx.inputs.size,
                outputCapacities = tx.outputs.map {
                    it.capacity.removePrefix("0x").toLongOrNull(16)
                },
            )

            // See selfRowDirectionAndAmount's KDoc (#538 review): a self
            // row's `amount` above undercounts whenever a non-change output
            // went to another script this wallet also owns, since the walk
            // that fed netChangeShannons was queried with only myScript.
            // feeShannons is computed from the full declared transaction, so
            // it is preferred when known; when it is not, and the abs(net)
            // fallback cannot be vouched for either, the row is demoted back
            // to "out" rather than showing a possibly-wrong amount under
            // "Self Transfer".
            val (selfRowDirection, selfRowAmount) = if (direction == "self") {
                selfRowDirectionAndAmount(feeShannons, tx.outputs, myScript.args, amount)
            } else {
                direction to amount
            }

            // #538 review: a gap-limit sweep spends FOUND candidate cells
            // (derived addresses) back to the main address. Those inputs are
            // never visible to this walk (queried with only myScript), so
            // netChangeShannons only sees the main-script output and reads
            // positive: it would otherwise show as "Received", hiding the fee
            // actually paid. The sweep records its own tx hash AND fee at
            // send time, scoped to walletId+network, handed in here as
            // [sweepFeeShannons], so this is detected without an extra
            // per-candidate-script lookup on every call. That marker is
            // trusted only together with a fresh isSelfTransferSignature
            // check against THIS viewing wallet's own self set (defence in
            // depth), and a genuine receive from a foreign input passes
            // neither check and is untouched. Only checked in the non-DAO
            // branch below, so DAO priority is unaffected.
            val recordedSweepFee = sweepFeeShannons(txHash)
            val sweepOutputsAreSelf = isSelfTransferSignature(
                netChangeShannons = -1L,
                outputs = tx.outputs,
                knownLockArgs = selfWalletLockArgs,
            )
            val sweepDisplay = sweepRowDisplay(
                isKnownSweepTxHash = recordedSweepFee != null,
                outputsAreSelf = sweepOutputsAreSelf,
                recordedFeeShannons = recordedSweepFee,
            )

            val (finalDirection, finalAmount, finalFeeShannons) = if (hasDaoOutput) {
                val daoOutputCapacity = tx.outputs
                    .first { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
                    .capacity.removePrefix("0x").toLongOrNull(16) ?: 0L
                if (tx.headerDeps.isEmpty()) {
                    Triple("dao_deposit", daoOutputCapacity, feeShannons)
                } else {
                    Triple("dao_withdraw", daoOutputCapacity, feeShannons)
                }
            } else if (tx.headerDeps.size >= 2) {
                // Unlock: show total CKB returned (deposit + compensation)
                val totalOutput = cellInteractions
                    .filter { it.ioType == "output" }
                    .sumOf { it.ioCapacity.removePrefix("0x").toLongOrNull(16) ?: 0L }
                Triple("dao_unlock", totalOutput, feeShannons)
            } else if (sweepDisplay != null) {
                Triple(sweepDisplay.direction, sweepDisplay.amountShannons, recordedSweepFee)
            } else {
                Triple(selfRowDirection, selfRowAmount, feeShannons)
            }

            TransactionRecord(
                txHash = txHash,
                blockNumber = firstInteraction.blockNumber,
                blockHash = headerInfo.hash ?: "0x0",
                timestamp = 0L,
                balanceChange = "0x${finalAmount.toString(16)}",
                direction = finalDirection,
                fee = "0x0",
                confirmations = confirmations,
                blockTimestampHex = headerInfo.timestampHex,
                isDaoRelated = hasDaoOutput || tx.headerDeps.size >= 2,
                feeShannons = finalFeeShannons
            )
        }

        // --- Cache write: upsert the COMPLETE walked history. The Activity tab
        // pages from the same store, so completeness here is what makes its
        // "All" list actually mean all. ---
        transactionStore.cacheTransactions(items, network.name, walletId = activeWalletId)

        // Sticky: once armed, only the Tier 2 deep scan clears it. Detection
        // is not re-evaluated downward — a later partial walk (runaway cap)
        // must not un-detect.
        if (gapLimitSignal && activeWalletId.isNotEmpty() &&
            !syncPreferences.isGapLimitSignalDetected(network, activeWalletId)
        ) {
            syncPreferences.setGapLimitSignalDetected(true, network, activeWalletId)
        }

        // Merge: include pending local txs not yet returned by the light client
        val jniTxHashes = items.map { it.txHash }.toSet()
        val pendingLocal = transactionStore.getPendingNotIn(network.name, jniTxHashes, walletId = activeWalletId)
        // Return only the newest `limit` transactions — Home renders this list
        // directly and the full set lives in the store. Cursor is always null now:
        // no UI caller ever passed one (Paging does the scrolling), and
        // the full walk leaves nothing to continue from.
        val mergedItems = (pendingLocal + items)
            .take(limit)
            // Badge batches of a bulk airdrop (marked at send time). Computed
            // from the persisted set so it survives the pending->confirmed and
            // cache-resync transitions.
            .map { it.copy(isBulk = uiPreferences.isBulkTxHash(it.txHash)) }

        TransactionsResponse(mergedItems, null)
    }

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> = runCatching {
        logger.d(TAG, "🔍 getTransactionStatus: Checking status for $txHash")

        val resJson = lightClient.getTransaction(txHash)
        if (resJson == null) {
            logger.w(TAG, "⚠️ getTransactionStatus: Native returned null for $txHash")
            // Return unknown status instead of throwing - tx might still be in network mempool
            return@runCatching TransactionStatusResponse(
                txHash = txHash,
                status = "unknown",
                confirmations = 0,
                blockHash = null
            )
        }

        logger.d(TAG, "📦 getTransactionStatus: Response: ${resJson.take(500)}")
        val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(resJson)

        val status = txWithStatus.txStatus.status
        logger.d(TAG, "📊 getTransactionStatus: Raw status='$status', blockHash=${txWithStatus.txStatus.blockHash}")

        // Calculate actual confirmations from tip - txBlock
        val confirmations = if (status == "committed" && txWithStatus.txStatus.blockHash != null) {
            val tipJson = lightClient.getTipHeader()
            if (tipJson != null) {
                val tip = json.decodeFromString<JniHeaderView>(tipJson)
                val tipNumber = tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L

                // Get tx's block header to compute real confirmation depth
                val txBlockJson = lightClient.getHeader(txWithStatus.txStatus.blockHash!!)
                if (txBlockJson != null) {
                    val txBlock = json.decodeFromString<JniHeaderView>(txBlockJson)
                    val txBlockNumber = txBlock.number.removePrefix("0x").toLongOrNull(16)
                    val realConfirmations = if (txBlockNumber != null) {
                        (tipNumber - txBlockNumber + 1).coerceAtLeast(1).toInt()
                    } else {
                        1 // malformed tx-block number; committed means at least 1
                    }
                    logger.d(TAG, "📈 Tip: $tipNumber, txBlock: $txBlockNumber, confirmations: $realConfirmations")
                    realConfirmations
                } else {
                    // Can't get tx block header — committed means at least 1
                    logger.d(TAG, "📈 Tip: $tipNumber, txBlock header unavailable, using 1")
                    1
                }
            } else {
                1 // At least 1 confirmation if committed
            }
        } else {
            0
        }

        logger.d(TAG, "✅ getTransactionStatus: status=$status, confirmations=$confirmations")

        TransactionStatusResponse(
            txHash = txHash,
            status = status,
            confirmations = confirmations,
            blockHash = txWithStatus.txStatus.blockHash
        )
    }

    /**
     * Get the current block number from the registered script in the light client.
     * This represents how far the light client has synced for our wallet.
     * In multi-wallet mode, matches the active wallet's script by lock args.
     *
     * Blocking and non-suspending, exactly as it was on the repository: every
     * caller is already off the main thread.
     */
    fun existingScriptBlock(activeScriptArgs: String?): Long {
        return try {
            val scriptsJson = lightClient.getScripts() ?: return 0L
            val scripts = json.decodeFromString<List<JniScriptStatus>>(scriptsJson)
            val match = if (activeScriptArgs != null) {
                scripts.find { it.script.args == activeScriptArgs }
            } else {
                scripts.firstOrNull()
            }
            match?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        } catch (e: Exception) {
            logger.w(TAG, "Failed to get existing script block: ${e.message}")
            0L
        }
    }

    companion object {
        private const val TAG = "LedgerReader"

        /**
         * Cursor-walk caps — runaway guards far above real usage
         * (100 items/page → 20k txs, 5k cells). Hitting one is logged.
         */
        private const val MAX_TX_PAGES = 200
        private const val MAX_CELL_PAGES = 50
    }
}
