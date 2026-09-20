package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.SendContext
import com.rjnr.pocketnode.data.send.SendPipeline
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.nervosnetwork.ckblightclient.LightClientNative

/**
 * The Nervos DAO surface, extracted from [GatewayRepository] (#460).
 *
 * Everything the DAO screen drives lives here: listing deposits (live scan,
 * cached-row merge, pending-withdraw overlay), the deeper rescan for deposits
 * that predate the sync window, and the three chain operations (deposit,
 * phase-1 withdraw, phase-2 unlock) with their V2 key-supplied overloads.
 *
 * These are the repository's bodies MOVED, not rewritten. The ordering, the
 * pending-row bookkeeping, the reservation-filter routing and the error
 * strings are load-bearing and all unchanged. [GatewayRepository] keeps a
 * one-line forward per public member, so ViewModels see the same API.
 *
 * The repository state a DAO call needs (active wallet, network, address,
 * signing key, the script-registration and send seams) arrives as
 * [DaoContext]. Its members are suppliers rather than snapshots on purpose:
 * each one is read at exactly the point the original code read it, so a
 * wallet switch mid-operation is observed the same way it was before.
 */
class DaoGateway(
    private val appDatabase: AppDatabase,
    private val daoSyncManager: DaoSyncManager,
    private val daoDepositReader: DaoDepositReader,
    private val daoHeaderResolver: DaoHeaderResolver,
    private val lightClient: LightClientReadOnly,
    private val transactionBuilder: TransactionBuilder,
    private val sendPipeline: SendPipeline,
    private val logger: Logger,
) {

    /**
     * The repository-owned state and seams one DAO call reads. Supplied per
     * call by `GatewayRepository.daoContext()`.
     */
    class DaoContext(
        val network: () -> NetworkType,
        val walletId: () -> String,
        val walletInfo: () -> WalletInfo?,
        val currentAddress: () -> String?,
        /** The active script's current sync head, via `LedgerReader`. */
        val existingScriptBlock: () -> Long,
        val privateKey: suspend () -> ByteArray,
        /** Sender snapshot for one [SendPipeline] call, same as the transfer path. */
        val sendContext: () -> SendContext,
        /**
         * Chain status of one transaction, via `LedgerReader`. Consulted only
         * to settle an unlock marker the local cache never scored (#529), so
         * it costs nothing on an ordinary refresh.
         */
        val transactionStatus: suspend (String) -> Result<TransactionStatusResponse>,
        /** statuses, walletIds, cmd, allowRewind -> accepted. */
        val setScriptsAndRecord: suspend (List<JniScriptStatus>, List<String>, Int, Boolean) -> Boolean,
    )

    // Cache-first header read; the resolver itself is network-agnostic, the
    // network arg is supplied here (#106 phase 2).
    private suspend fun getOrFetchHeader(ctx: DaoContext, blockHash: String): JniHeaderView? =
        daoHeaderResolver.getOrFetchHeader(blockHash, ctx.network())

    suspend fun getDaoDeposits(ctx: DaoContext): Result<List<DaoDeposit>> = runCatching {
        val info = ctx.walletInfo() ?: throw Exception("No wallet")
        val currentEpoch = lightClient.getCurrentEpoch().getOrNull()
        val live = daoDepositReader.list(info.script, currentEpoch, ctx.network())
        // #357: drop a spent deposit's stale DEPOSITED entry that the light
        // client still lists alongside its new withdrawing cell, before the
        // pending-withdraw overlay would paint it a duplicate "Confirming…".
        val deduped = dedupeWithdrawnDeposits(live)
        // #529: read the phase-2 markers ONCE, before anything consumes them.
        // The merge needs them to decide whether an absent cell was spent, and
        // the overlay deletes the markers it resolves, so the overlay must not
        // run first or the merge would see no marker on the very poll where
        // the unlock resolves.
        val pendingUnlocks = readPendingUnlocks(ctx, deduped)
        val merged = mergeWithCachedDaoDeposits(ctx, deduped, pendingUnlocks)
        applyPendingUnlockOverlay(ctx, applyPendingWithdrawOverlay(ctx, merged), pendingUnlocks)
    }

    /**
     * #347: overlay in-flight phase-1 withdraws onto the deposit list. The
     * deposit cell scans as DEPOSITED until the withdraw commits and is
     * indexed, so without this a just-withdrawn deposit looks withdrawable
     * again (double-withdraw) and the only "withdrawing" signal — the
     * in-memory banner — is lost on restart. The persisted marker carries the
     * state across process death; [resolvePendingWithdraw] decides per marker
     * whether to overlay WITHDRAWING, or retire it (committed / failed).
     */
    private suspend fun applyPendingWithdrawOverlay(ctx: DaoContext, deposits: List<DaoDeposit>): List<DaoDeposit> {
        val walletId = ctx.walletId()
        if (walletId.isEmpty()) return deposits
        val network = ctx.network().name
        val withdrawDao = appDatabase.pendingDaoWithdrawDao()
        val pending = runCatching { withdrawDao.getByWalletAndNetwork(walletId, network) }
            .getOrDefault(emptyList())
        if (pending.isEmpty()) return deposits

        fun key(txHash: String, index: String) = "$txHash:$index"
        val depositedKeys = deposits
            .filter { it.status == DaoCellStatus.DEPOSITED }
            .map { key(it.outPoint.txHash, it.outPoint.index) }
            .toSet()

        val overlayKeys = mutableSetOf<String>()
        for (p in pending) {
            val k = key(p.depositTxHash, p.depositIndex)
            val stillDeposited = k in depositedKeys
            val txStatus = runCatching {
                appDatabase.transactionDao().getByTxHash(p.withdrawTxHash)?.status
            }.getOrNull()
            when (resolvePendingWithdraw(stillDeposited, txStatus)) {
                PendingWithdrawResolution.OVERLAY -> if (stillDeposited) overlayKeys.add(k)
                PendingWithdrawResolution.CLEAR_CONFIRMED,
                PendingWithdrawResolution.CLEAR_FAILED ->
                    runCatching { withdrawDao.deleteByDeposit(p.depositTxHash, p.depositIndex) }
            }
        }
        if (overlayKeys.isEmpty()) return deposits
        return deposits.map {
            if (key(it.outPoint.txHash, it.outPoint.index) in overlayKeys) {
                it.copy(status = DaoCellStatus.WITHDRAWING)
            } else it
        }
    }

    /**
     * One persisted phase-2 marker plus the unlock transaction's locally
     * cached status, read together so the merge and the overlay decide on
     * exactly the same facts within one [getDaoDeposits] call (#529).
     */
    private class PendingUnlock(
        val entity: com.rjnr.pocketnode.data.database.entity.PendingDaoUnlockEntity,
        val state: DaoUnlockMarkerState,
        val stillLive: Boolean,
    ) {
        val cellKey: String = normalizedOutPointKey(entity.withdrawingTxHash, entity.withdrawingIndex)
    }

    /**
     * Every cached DAO row of the active wallet, active and completed alike,
     * indexed by normalized outpoint and carrying the exact (txHash, index)
     * strings it is stored under, which is what a status write must name.
     */
    private suspend fun cachedRowKeys(ctx: DaoContext): Map<String, Pair<String, String>> {
        val walletId = ctx.walletId().takeIf { it.isNotEmpty() } ?: return emptyMap()
        val network = ctx.network().name
        val rows = runCatching {
            daoSyncManager.getActiveDeposits(network, walletId) +
                daoSyncManager.getCompletedDeposits(network, walletId)
        }.getOrDefault(emptyList())
        return rows.associate {
            normalizedOutPointKey(it.txHash, it.index) to (it.txHash to it.index)
        }
    }

    /**
     * What the chain says about an unlock transaction, or
     * [DaoUnlockChainVerdict.UNKNOWN] when it will not say (#529). Every
     * failure mode collapses to UNKNOWN on purpose: the caller must never
     * read "could not tell" as "spent".
     */
    private suspend fun chainVerdictFor(ctx: DaoContext, txHash: String): DaoUnlockChainVerdict {
        val status = runCatching { ctx.transactionStatus(txHash).getOrNull() }.getOrNull()
            ?: return DaoUnlockChainVerdict.UNKNOWN
        return when {
            status.status == "committed" -> DaoUnlockChainVerdict.COMMITTED
            status.status == "rejected" -> DaoUnlockChainVerdict.REJECTED
            status.isPending() -> DaoUnlockChainVerdict.IN_POOL
            else -> DaoUnlockChainVerdict.UNKNOWN
        }
    }

    /**
     * Every phase-2 marker for the active wallet, resolved against the
     * transaction cache, the marker's age and [liveDeposits] (the scan the
     * refresh is working from, before any cached rows are merged in).
     */
    private suspend fun readPendingUnlocks(
        ctx: DaoContext,
        liveDeposits: List<DaoDeposit>,
    ): List<PendingUnlock> {
        val walletId = ctx.walletId()
        if (walletId.isEmpty()) return emptyList()
        val rows = runCatching {
            appDatabase.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, ctx.network().name)
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()
        val liveKeys = liveDeposits
            .map { normalizedOutPointKey(it.outPoint.txHash, it.outPoint.index) }
            .toSet()
        val now = System.currentTimeMillis()
        return rows.map { row ->
            val status = runCatching {
                appDatabase.transactionDao().getByTxHash(row.unlockTxHash)?.status
            }.getOrNull()
            val ageMs = (now - row.createdAt).coerceAtLeast(0L)
            // Only an old marker the local cache never scored is worth a chain
            // round trip, and only then can a marker be promoted to CONFIRMED.
            // CacheManager swallows a failed pending-transaction insert and the
            // watchdog scores only while the app is running, so "no local row"
            // says nothing at all about what happened on chain.
            val verdict = if (
                ageMs >= DAO_UNLOCK_MARKER_GRACE_MS && status != "CONFIRMED" && status != "FAILED"
            ) {
                chainVerdictFor(ctx, row.unlockTxHash)
            } else {
                DaoUnlockChainVerdict.UNKNOWN
            }
            PendingUnlock(
                entity = row,
                state = daoUnlockMarkerState(
                    unlockTxStatus = status,
                    markerAgeMs = ageMs,
                    chainVerdict = verdict,
                ),
                stillLive = normalizedOutPointKey(row.withdrawingTxHash, row.withdrawingIndex) in liveKeys,
            )
        }
    }

    /**
     * #529: overlay in-flight phase-2 unlocks onto the deposit list.
     *
     * The withdrawing cell keeps scanning UNLOCKABLE from the moment the
     * unlock is broadcast until the light client indexes the spend, minutes
     * during which the card offered "Unlock" again and accepted a second tap
     * that could only ever fail. This paints the cell UNLOCKING for that
     * window, from a persisted marker so a relaunch mid-unlock still shows a
     * spinner instead of a live Unlock button.
     *
     * A marker is deleted only once its transaction is terminal, so a later
     * failure can still hand the position back. On success the cached row is
     * marked COMPLETED at the same time; on failure it is restored to
     * UNLOCKABLE, which matters for a deposit the light client cannot see:
     * the cached row is then the only record that it exists at all.
     */
    private suspend fun applyPendingUnlockOverlay(
        ctx: DaoContext,
        deposits: List<DaoDeposit>,
        pendingUnlocks: List<PendingUnlock>,
    ): List<DaoDeposit> {
        if (pendingUnlocks.isEmpty()) return deposits
        val unlockDao = appDatabase.pendingDaoUnlockDao()
        // dao_cells is keyed by the exact strings the row was written with, so
        // the status write has to name the row's OWN spelling of the index.
        // Using the marker's would silently update zero rows while the marker
        // itself was deleted, losing the verdict entirely.
        val cachedKeys = cachedRowKeys(ctx)

        suspend fun retireMarker(p: PendingUnlock, status: DaoCellStatus) {
            val row = cachedKeys[p.cellKey]
            runCatching {
                daoSyncManager.updateStatus(
                    row?.first ?: p.entity.withdrawingTxHash,
                    row?.second ?: p.entity.withdrawingIndex,
                    status.name,
                )
            }
            runCatching {
                unlockDao.deleteByWithdrawingCell(
                    p.entity.withdrawingTxHash,
                    p.entity.withdrawingIndex,
                )
            }
        }

        val overlayKeys = mutableSetOf<String>()
        for (p in pendingUnlocks) {
            when (resolvePendingUnlock(p.stillLive, p.state)) {
                PendingUnlockResolution.OVERLAY -> overlayKeys.add(p.cellKey)
                PendingUnlockResolution.RETIRE -> retireMarker(p, DaoCellStatus.COMPLETED)
                // The cell is the user's again. UNLOCKABLE is the state it was
                // in when they tapped: a withdrawing cell whose lock period is
                // over does not go back.
                PendingUnlockResolution.RESTORE -> retireMarker(p, DaoCellStatus.UNLOCKABLE)
            }
        }
        if (overlayKeys.isEmpty()) return deposits
        return deposits.map {
            if (normalizedOutPointKey(it.outPoint.txHash, it.outPoint.index) in overlayKeys) {
                it.copy(status = DaoCellStatus.UNLOCKING)
            } else it
        }
    }

    /**
     * Deposit outpoints with an in-flight withdraw (#347) — used by the DAO
     * screen to rehydrate the "Withdrawing from DAO…" banner after restart.
     */
    suspend fun getInFlightWithdrawOutPoints(ctx: DaoContext): List<OutPoint> {
        val walletId = ctx.walletId().takeIf { it.isNotEmpty() } ?: return emptyList()
        return runCatching {
            appDatabase.pendingDaoWithdrawDao()
                .getByWalletAndNetwork(walletId, ctx.network().name)
                .map { OutPoint(it.depositTxHash, it.depositIndex) }
        }.getOrDefault(emptyList())
    }

    /**
     * #332 windowing recovery. The light client only indexes cells created
     * AFTER the script's registered start block, so a DAO deposit older than
     * the chosen sync window silently vanishes from both the deposit list and
     * the balance. Mitigation:
     *  1. write-through: every live scan persists its deposits to dao_cells;
     *  2. reconcile: cached active deposits INSIDE the window that the live
     *     scan no longer returns were spent/unlocked — mark COMPLETED;
     *  3. merge: cached active deposits from BEFORE the window are appended,
     *     flagged [DaoDeposit.outsideSyncWindow] so the UI can offer the
     *     deeper-rescan recovery instead of pretending they don't exist.
     */
    private suspend fun mergeWithCachedDaoDeposits(
        ctx: DaoContext,
        live: List<DaoDeposit>,
        pendingUnlocks: List<PendingUnlock>,
    ): List<DaoDeposit> {
        val walletId = ctx.walletId()
        if (walletId.isEmpty()) return live
        val network = ctx.network().name
        val nowMs = System.currentTimeMillis()

        runCatching {
            daoSyncManager.upsertDaoCells(live.map { it.toDaoCellEntity(network, walletId, nowMs) })
        }.onFailure { logger.w(TAG, "DAO write-through failed: ${it.message}") }

        val windowStart = ctx.existingScriptBlock()
        // Normalized, like every other outpoint comparison here: a raw compare
        // let one cell count as live AND absent when the cached row and the
        // scan spelled the index differently, which appended a duplicate card
        // for it further down.
        val liveKeys = live
            .map { normalizedOutPointKey(it.outPoint.txHash, it.outPoint.index) }
            .toSet()

        // #434: outpoints consumed by a live withdrawing cell's phase-1 tx.
        // A cached deposit whose outpoint is here was spent by a withdraw and
        // MUST be retired — never resurfaced as an outside-window entry.
        // ctx.existingScriptBlock() returns the script's current sync head, not
        // its registration start, so a just-spent recent deposit (block now
        // behind the head) would otherwise fall into the `< windowStart` branch,
        // reappear under "made before this wallet's sync window", and double the
        // DAO total right after a withdraw confirms. Index formats are
        // normalized (hex vs decimal) so the outpoint match is reliable.
        val consumedByLive = live.flatMap { it.consumedDepositOutPoints }
            .map { normalizedOutPointKey(it.txHash, it.index) }
            .toSet()

        // #529: the phase-2 counterpart of `consumedByLive`. An unlock's output
        // is a plain CKB cell, never a DAO cell, so NOTHING in the live scan
        // points back at the withdrawing cell it spent: the only record that
        // the spend happened is the marker this wallet wrote when it broadcast
        // the unlock. Without it the withdrawing row's cached deposit block
        // (the ORIGINAL deposit, always far behind the sync head) sent it
        // straight into the outside-window branch, where it kept rendering an
        // "Unlock" button for a cell that no longer exists.
        val unlockStates = pendingUnlocks.associate { it.cellKey to it.state }

        val cached = runCatching { daoSyncManager.getActiveDeposits(network, walletId) }
            .getOrDefault(emptyList())

        val outsideWindow = mutableListOf<DaoDeposit>()
        val confirming = mutableListOf<DaoDeposit>()
        for (entity in cached) {
            val normKey = normalizedOutPointKey(entity.txHash, entity.index)
            if (normKey in liveKeys) continue
            when (
                resolveCachedDaoCell(
                    cachedStatus = entity.status,
                    depositBlockNumber = entity.depositBlockNumber,
                    windowStart = windowStart,
                    consumedByLiveWithdraw = normKey in consumedByLive,
                    unlockState = unlockStates[normKey],
                )
            ) {
                CachedDaoCellFate.IGNORE -> Unit
                CachedDaoCellFate.RETIRE -> runCatching {
                    daoSyncManager.updateStatus(entity.txHash, entity.index, DaoCellStatus.COMPLETED.name)
                }
                // Still confirming: keep the position on screen so it reads
                // "Confirming…" (the overlay paints it) rather than vanishing
                // or, worse, reappearing as an unlockable outside-window row
                // for the minutes between broadcast and the tx being scored.
                CachedDaoCellFate.UNLOCK_IN_FLIGHT -> confirming += entity.toCachedDeposit()
                CachedDaoCellFate.OUTSIDE_WINDOW -> outsideWindow += entity.toOutsideWindowDeposit()
            }
        }
        if (outsideWindow.isNotEmpty()) {
            logger.i(TAG, "DAO merge: ${outsideWindow.size} cached deposit(s) predate sync window (start=$windowStart)")
        }
        return live + confirming + outsideWindow
    }

    /**
     * User-confirmed deeper rescan to re-index DAO deposits that predate the
     * current sync window (#332). Rewinds the wallet's lock script to just
     * before the oldest cached out-of-window deposit. Multi-hour cost on
     * mainnet — callers must gate behind an explicit confirmation dialog.
     * Returns the rewind target block.
     */
    suspend fun rescanForOlderDaoDeposits(ctx: DaoContext): Result<Long> = runCatching {
        val info = ctx.walletInfo() ?: throw Exception("No wallet")
        val walletId = ctx.walletId().takeIf { it.isNotEmpty() } ?: throw Exception("No active wallet")
        val windowStart = ctx.existingScriptBlock()
        val oldest = daoSyncManager.getActiveDeposits(ctx.network().name, walletId)
            .filter { it.status != DaoCellStatus.DEPOSITING.name }
            .filter { windowStart > 0 && it.depositBlockNumber in 1 until windowStart }
            .minOfOrNull { it.depositBlockNumber }
            ?: throw Exception("No deposits older than the current sync window")
        val target = (oldest - 100).coerceAtLeast(0L)
        val ok = ctx.setScriptsAndRecord(
            listOf(
                JniScriptStatus(
                    script = info.script,
                    scriptType = "lock",
                    blockNumber = "0x${target.toString(16)}"
                )
            ),
            listOf(walletId),
            LightClientNative.CMD_SET_SCRIPTS_PARTIAL,
            true, // allowRewind: explicitly user-initiated rewind
        )
        if (!ok) throw Exception("Light client refused script registration")
        logger.i(TAG, "DAO deep rescan: rewound script to block $target (oldest cached deposit at $oldest)")
        target
    }


    suspend fun depositToDao(ctx: DaoContext, amountShannons: Long): Result<String> =
        ctx.privateKey().let { key ->
            try {
                depositToDao(ctx, amountShannons, privateKey = key)
            } finally {
                key.fill(0) // transient signing key — zero after use (#321)
            }
        }

    /**
     * V2-aware overload: caller supplies the private key already
     * unlocked via BiometricPrompt CryptoObject. Used by [DaoViewModel]
     * when the active wallet is on `kdfVersion=2` and requires explicit
     * user authentication per signing operation (#213 sub-PR 5).
     */
    suspend fun depositToDao(
        ctx: DaoContext,
        amountShannons: Long,
        privateKey: ByteArray,
    ): Result<String> = runCatching {
        val info = ctx.walletInfo() ?: throw Exception("No wallet")
        val address = ctx.currentAddress() ?: throw Exception("No address")

        require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
            "Minimum deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
        }

        // Route through the shared mutex + reservation filter (#320) so a deposit
        // can't select inputs already reserved by an in-flight transfer.
        val txHash = sendPipeline.buildReserveAndSend(
            ctx = ctx.sendContext(),
            fromAddress = address,
            // #433: pending deposit reads "Dao Deposit <amount> CKB" (matching the
            // confirmed row) rather than a generic "Sent" carrying amount + fee.
            pendingDirection = "dao_deposit",
            pendingAmountShannons = amountShannons,
            // The builder prices the deposit from the tx it actually builds, so
            // the pending row quotes the same estimator for the common
            // one-input shape instead of the DEFAULT_FEE reservation, which
            // over-reported the fee 100x until the confirmed row replaced it
            // (#490). Corrected by the confirmed record either way.
            pendingFeeShannons = transactionBuilder.estimateTransferFee(
                inputCount = 1,
                outputCount = 2,
            ),
        ) { availableCells, net ->
            transactionBuilder.buildDaoDeposit(
                amountShannons = amountShannons,
                availableCells = availableCells,
                senderScript = info.script,
                privateKey = privateKey,
                network = net
            )
        }
        logger.d(TAG, "DAO deposit sent: $txHash")

        // Track pending deposit in Room so UI shows it before JNI confirms
        daoSyncManager.insertPendingDeposit(txHash, amountShannons, ctx.network().name, walletId = ctx.walletId())

        txHash
    }

    suspend fun withdrawFromDao(ctx: DaoContext, depositOutPoint: OutPoint): Result<String> =
        ctx.privateKey().let { key ->
            try {
                withdrawFromDao(ctx, depositOutPoint, privateKey = key)
            } finally {
                key.fill(0) // transient signing key — zero after use (#321)
            }
        }

    /** V2-aware overload — see [depositToDao]. */
    suspend fun withdrawFromDao(
        ctx: DaoContext,
        depositOutPoint: OutPoint,
        privateKey: ByteArray,
    ): Result<String> = runCatching {
        val info = ctx.walletInfo() ?: throw Exception("No wallet")
        val address = ctx.currentAddress() ?: throw Exception("No address")

        // Find the deposit cell
        val deposits = getDaoDeposits(ctx).getOrThrow()
        val deposit = deposits.find { it.outPoint == depositOutPoint }
            ?: throw Exception("Deposit not found")

        require(deposit.depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }

        // Build a Cell from the deposit for the transaction builder. The
        // deposit cell itself is a DAO (typed) cell and is NOT in getCells'
        // output, so it isn't subject to the regular-cell reservation filter.
        val depositCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${deposit.depositBlockNumber.toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT,
            data = "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
        )

        // Route through the shared mutex + reservation filter (#320). DAO Phase 1
        // preserves the deposit capacity exactly, so a regular fee input cell is
        // mandatory (#119) — `availableCells` is the reservation-filtered regular
        // CKB set, ensuring the fee cell isn't one an in-flight transfer reserved.
        val txHash = sendPipeline.buildReserveAndSend(
            ctx = ctx.sendContext(),
            fromAddress = address,
            // #433: show the pending withdraw as "Dao Withdraw <deposit> CKB"
            // with the fee on its own line, not as a "-0.001 Sent". Phase-1
            // preserves the deposit capacity exactly, so the fee is whatever
            // the fee cell pays: the same dynamic estimate the builder uses,
            // for the common deposit-plus-one-fee-cell shape (#490).
            pendingDirection = "dao_withdraw",
            pendingAmountShannons = deposit.capacity,
            pendingFeeShannons = transactionBuilder.estimateTransferFee(
                inputCount = 2,
                outputCount = 2,
            ),
        ) { availableCells, net ->
            transactionBuilder.buildDaoWithdraw(
                depositCell = depositCell,
                depositBlockNumber = deposit.depositBlockNumber,
                depositBlockHash = deposit.depositBlockHash,
                senderScript = info.script,
                privateKey = privateKey,
                network = net,
                availableCells = availableCells
            )
        }
        logger.d(TAG, "DAO withdraw (phase 1) sent: $txHash")

        // #347: persist the in-flight withdraw so the deposit renders as
        // WITHDRAWING ("Confirming…") across restart and can't be withdrawn
        // twice. Cleared by applyPendingWithdrawOverlay on commit/failure.
        runCatching {
            appDatabase.pendingDaoWithdrawDao().upsert(
                com.rjnr.pocketnode.data.database.entity.PendingDaoWithdrawEntity(
                    depositTxHash = depositOutPoint.txHash,
                    depositIndex = depositOutPoint.index,
                    withdrawTxHash = txHash,
                    walletId = ctx.walletId(),
                    network = ctx.network().name,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { logger.w(TAG, "Failed to persist pending withdraw marker: ${it.message}") }

        txHash
    }

    suspend fun unlockDao(ctx: DaoContext, withdrawingOutPoint: OutPoint): Result<String> =
        ctx.privateKey().let { key ->
            try {
                unlockDao(ctx, withdrawingOutPoint, privateKey = key)
            } finally {
                key.fill(0) // transient signing key — zero after use (#321)
            }
        }

    /** V2-aware overload — see [depositToDao]. */
    suspend fun unlockDao(
        ctx: DaoContext,
        withdrawingOutPoint: OutPoint,
        privateKey: ByteArray,
    ): Result<String> = runCatching {
        val info = ctx.walletInfo() ?: throw Exception("No wallet")
        val net = ctx.network()

        val deposits = getDaoDeposits(ctx).getOrThrow()
        // #529: a cell the unlock already spent is gone from the list. Fail
        // here with a terminal, readable message instead of building a
        // transaction against an outpoint that cannot be spent twice. The
        // caller clears its spinner on failure, so "not found" must never be
        // reachable for a position the user has in fact already claimed.
        val deposit = deposits.find { it.outPoint == withdrawingOutPoint }
            ?: throw Exception(missingWithdrawingCellMessage(ctx, withdrawingOutPoint))

        if (deposit.status == DaoCellStatus.UNLOCKING) {
            throw Exception(
                alreadyUnlockingMessage(unlockClaimState(ctx, withdrawingOutPoint).unlockTxHash)
            )
        }
        require(deposit.status == DaoCellStatus.UNLOCKABLE) {
            "Cell is not unlockable yet (status: ${deposit.status})"
        }

        // Use the deposit object's hashes — it is the single source of truth
        val depositBlockHash = deposit.depositBlockHash
        require(depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }
        val withdrawBlockHash = deposit.withdrawBlockHash
            ?: throw Exception("Withdraw block hash unavailable. Please retry after sync.")

        // Get headers for max withdraw calculation (cache-first)
        val depositHeader = getOrFetchHeader(ctx, depositBlockHash)
            ?: throw Exception("Failed to get deposit header")

        val withdrawHeader = getOrFetchHeader(ctx, withdrawBlockHash)
            ?: throw Exception("Failed to get withdraw header")

        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
            depositHeader.dao,
            withdrawHeader.dao,
            deposit.capacity,
            DaoConstants.DEPOSIT_OCCUPIED_SHANNONS
        )
        if (maxWithdraw < 0) throw Exception("Failed to calculate max withdraw capacity")

        val sinceValue = LightClientNative.nativeCalculateUnlockEpoch(
            depositHeader.epoch,
            withdrawHeader.epoch
        ) ?: throw Exception("Failed to calculate unlock epoch")

        val withdrawingCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${(deposit.withdrawBlockNumber ?: throw Exception("No withdraw block")).toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT
        )

        val tx = transactionBuilder.buildDaoUnlock(
            withdrawingCell = withdrawingCell,
            maxWithdraw = maxWithdraw,
            sinceValue = sinceValue,
            depositBlockHash = depositBlockHash,
            withdrawBlockHash = withdrawBlockHash,
            senderScript = info.script,
            privateKey = privateKey,
            network = net
        )

        // The unlock's fee is knowable exactly here and NOWHERE ELSE (#497).
        // On-chain the tx reads: one input whose declared capacity is the
        // original deposit, one output worth maxWithdraw − fee. Since
        // maxWithdraw = deposit + compensation, inputs − outputs comes out as
        // fee − compensation, i.e. negative, and the confirmed-path formula
        // can never score it. Recovering the compensation from the confirmed
        // transaction would take two header fetches plus a JNI
        // calculateMaxWithdraw per row, and would first have to decode the
        // witness input_type to learn WHICH header dep is the deposit (the
        // order is not load-bearing — see TransactionBuilder.buildDaoUnlock),
        // which we cannot rely on for a tx we did not build. So the planned
        // fee is persisted on the pending row and carried forward by
        // CacheManager; an unlock with no recorded fee hides the row rather
        // than promising a "Pending" that would never resolve.
        val plannedFeeShannons = daoUnlockFeeShannons(
            maxWithdraw = maxWithdraw,
            outputCapacities = tx.cellOutputs.map {
                it.capacity.removePrefix("0x").toLongOrNull(16)
            },
        )

        // Unlock consumes only the withdrawing DAO cell (typed; never returned by
        // getCells, so no transfer can select it) and pays the fee from that
        // cell's own capacity — it selects no regular cells, so the
        // reservation filter doesn't apply. sendTransaction still reserves this
        // input and serializes the pre-broadcast insert under sendMutex (#320).
        val txHash = sendPipeline.sendTransaction(
            ctx = ctx.sendContext(),
            transaction = tx,
            pendingFeeShannons = plannedFeeShannons,
        ).getOrThrow()
        logger.d(TAG, "DAO unlock (phase 2) sent: $txHash")

        // #529: persist the in-flight unlock, the phase-2 twin of the #347
        // withdraw marker. Until the spend is indexed the withdrawing cell
        // still scans UNLOCKABLE, so without this row the card re-offers
        // "Unlock" (and accepts the tap) while this transaction is in flight,
        // and the cached dao_cells row is never retired once it commits.
        runCatching {
            appDatabase.pendingDaoUnlockDao().upsert(
                com.rjnr.pocketnode.data.database.entity.PendingDaoUnlockEntity(
                    withdrawingTxHash = withdrawingOutPoint.txHash,
                    withdrawingIndex = withdrawingOutPoint.index,
                    unlockTxHash = txHash,
                    walletId = ctx.walletId(),
                    network = net.name,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { logger.w(TAG, "Failed to persist pending unlock marker: ${it.message}") }

        txHash
    }

    /** What this device's own records say about a withdrawing cell (#529). */
    private enum class UnlockClaim { NONE, IN_FLIGHT, RETIRED }

    /** [UnlockClaim] plus the unlock transaction behind it, when there is one. */
    private class UnlockClaimResult(val claim: UnlockClaim, val unlockTxHash: String? = null)

    /**
     * Whether this position is already claimed or mid-claim, from the two
     * Room reads alone. No chain access, so it is cheap enough to run before
     * asking the user to authenticate.
     *
     * Both lookups normalize the outpoint index, because the spelling the UI
     * hands back (hex vs decimal) need not match the spelling the row was
     * written with, and a mismatch would silently answer "not claimed".
     */
    private suspend fun unlockClaimState(ctx: DaoContext, outPoint: OutPoint): UnlockClaimResult {
        val walletId = ctx.walletId()
        if (walletId.isEmpty()) return UnlockClaimResult(UnlockClaim.NONE)
        val network = ctx.network().name
        val key = normalizedOutPointKey(outPoint.txHash, outPoint.index)
        val marker = runCatching {
            appDatabase.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network)
                .firstOrNull { normalizedOutPointKey(it.withdrawingTxHash, it.withdrawingIndex) == key }
        }.getOrNull()
        if (marker != null) return UnlockClaimResult(UnlockClaim.IN_FLIGHT, marker.unlockTxHash)
        val retired = runCatching {
            daoSyncManager.getCompletedDeposits(network, walletId)
                .any { normalizedOutPointKey(it.txHash, it.index) == key }
        }.getOrDefault(false)
        return UnlockClaimResult(if (retired) UnlockClaim.RETIRED else UnlockClaim.NONE)
    }

    /**
     * Fail an unlock the wallet already knows is pointless BEFORE the caller
     * spends a biometric or PIN prompt on it (#529). Success means only "no
     * local record says this is already claimed": [unlockDao] still does the
     * full check against the live list.
     */
    suspend fun unlockPreflight(ctx: DaoContext, withdrawingOutPoint: OutPoint): Result<Unit> = runCatching {
        val claim = unlockClaimState(ctx, withdrawingOutPoint)
        when (claim.claim) {
            UnlockClaim.RETIRED -> throw Exception(ALREADY_UNLOCKED_MESSAGE)
            UnlockClaim.IN_FLIGHT -> throw Exception(alreadyUnlockingMessage(claim.unlockTxHash))
            UnlockClaim.NONE -> Unit
        }
    }

    /**
     * Why the withdrawing cell the user tapped is not in the list: already
     * claimed (its row is retired, or an unlock for it is on record), or
     * genuinely not visible yet.
     */
    private suspend fun missingWithdrawingCellMessage(
        ctx: DaoContext,
        outPoint: OutPoint,
    ): String {
        val claim = unlockClaimState(ctx, outPoint)
        return when (claim.claim) {
            UnlockClaim.RETIRED -> ALREADY_UNLOCKED_MESSAGE
            UnlockClaim.IN_FLIGHT -> alreadyUnlockingMessage(claim.unlockTxHash)
            UnlockClaim.NONE -> "Withdrawing cell not found"
        }
    }


    companion object {
        private const val TAG = "DaoGateway"

        /** Terminal failure for a position whose unlock already went through (#529). */
        const val ALREADY_UNLOCKED_MESSAGE = "This deposit was already unlocked"

        /** Terminal failure for a second tap while the first unlock is in flight (#529). */
        const val ALREADY_UNLOCKING_MESSAGE = "This deposit is already being unlocked"

        /**
         * The same refusal, naming the transaction the user is waiting on so
         * they can look it up in an explorer rather than only being told to
         * wait. A marker that never resolves blocks the position until the
         * chain settles it, so the hash is the one thing that lets the user
         * find out why.
         */
        fun alreadyUnlockingMessage(unlockTxHash: String?): String =
            if (unlockTxHash.isNullOrBlank()) ALREADY_UNLOCKING_MESSAGE
            else "$ALREADY_UNLOCKING_MESSAGE (tx ${shortenTxHash(unlockTxHash)})"

        /** "0x1234abcd...5678ef90", enough to recognise without filling a snackbar. */
        private fun shortenTxHash(txHash: String): String =
            if (txHash.length <= 22) txHash else "${txHash.take(10)}...${txHash.takeLast(8)}"

        /**
         * Outpoint key with the index normalized (hex vs decimal) so a match
         * across the live scan, the dao_cells cache and the pending markers is
         * reliable whatever spelling each recorded.
         */
        private fun normalizedOutPointKey(txHash: String, index: String): String =
            "${txHash.lowercase()}:${index.removePrefix("0x").toLongOrNull(16) ?: index}"
    }
}
