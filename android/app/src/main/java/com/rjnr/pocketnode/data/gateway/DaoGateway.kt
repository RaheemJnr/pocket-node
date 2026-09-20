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
        applyPendingWithdrawOverlay(ctx, mergeWithCachedDaoDeposits(ctx, deduped))
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
    private suspend fun mergeWithCachedDaoDeposits(ctx: DaoContext, live: List<DaoDeposit>): List<DaoDeposit> {
        val walletId = ctx.walletId()
        if (walletId.isEmpty()) return live
        val network = ctx.network().name
        val nowMs = System.currentTimeMillis()

        runCatching {
            daoSyncManager.upsertDaoCells(live.map { it.toDaoCellEntity(network, walletId, nowMs) })
        }.onFailure { logger.w(TAG, "DAO write-through failed: ${it.message}") }

        val windowStart = ctx.existingScriptBlock()
        val liveKeys = live.map { "${it.outPoint.txHash}:${it.outPoint.index}" }.toSet()

        // #434: outpoints consumed by a live withdrawing cell's phase-1 tx.
        // A cached deposit whose outpoint is here was spent by a withdraw and
        // MUST be retired — never resurfaced as an outside-window entry.
        // ctx.existingScriptBlock() returns the script's current sync head, not
        // its registration start, so a just-spent recent deposit (block now
        // behind the head) would otherwise fall into the `< windowStart` branch,
        // reappear under "made before this wallet's sync window", and double the
        // DAO total right after a withdraw confirms. Index formats are
        // normalized (hex vs decimal) so the outpoint match is reliable.
        fun normKey(txHash: String, index: String) =
            "${txHash.lowercase()}:${index.removePrefix("0x").toLongOrNull(16) ?: index}"
        val consumedByLive = live.flatMap { it.consumedDepositOutPoints }
            .map { normKey(it.txHash, it.index) }
            .toSet()

        val cached = runCatching { daoSyncManager.getActiveDeposits(network, walletId) }
            .getOrDefault(emptyList())

        val outsideWindow = mutableListOf<DaoDeposit>()
        for (entity in cached) {
            val key = "${entity.txHash}:${entity.index}"
            if (key in liveKeys) continue
            // DEPOSITING rows are optimistic pre-confirmation inserts with
            // blockNumber 0 — not windowing victims; leave them alone.
            if (entity.status == DaoCellStatus.DEPOSITING.name) continue
            if (normKey(entity.txHash, entity.index) in consumedByLive) {
                // Spent by a live withdraw — retire so it neither resurfaces as
                // an outside-window entry nor double-counts the DAO total (#434).
                runCatching {
                    daoSyncManager.updateStatus(entity.txHash, entity.index, DaoCellStatus.COMPLETED.name)
                }
            } else if (windowStart > 0 && entity.depositBlockNumber in 1 until windowStart) {
                outsideWindow += entity.toOutsideWindowDeposit()
            } else {
                // Inside the window yet absent from the live scan: the cell
                // was spent (withdrawn/unlocked) — retire the cached row so it
                // doesn't resurrect.
                runCatching {
                    daoSyncManager.updateStatus(entity.txHash, entity.index, DaoCellStatus.COMPLETED.name)
                }
            }
        }
        if (outsideWindow.isNotEmpty()) {
            logger.i(TAG, "DAO merge: ${outsideWindow.size} cached deposit(s) predate sync window (start=$windowStart)")
        }
        return live + outsideWindow
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
        val deposit = deposits.find { it.outPoint == withdrawingOutPoint }
            ?: throw Exception("Withdrawing cell not found")

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
        txHash
    }


    companion object {
        private const val TAG = "DaoGateway"
    }
}
