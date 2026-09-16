package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientApi
import com.rjnr.pocketnode.data.gateway.OutgoingOutput
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.computeFeeShannons
import com.rjnr.pocketnode.data.gateway.computeOutgoingShannons
import com.rjnr.pocketnode.data.gateway.recipientOutgoingShannons
import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.CellsResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.Transaction
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import com.rjnr.pocketnode.data.storage.PendingBroadcastStore
import com.rjnr.pocketnode.data.storage.TransactionStore
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.transaction.Signer
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.transaction.TransferPlan
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/**
 * The wallet state one send is pinned to, captured before anything is built.
 *
 * The user can switch wallet or network mid-send (rare, but Settings is one
 * tap away), so every piece of sender identity arrives here as a VALUE rather
 * than being re-read live inside the mutex. Two members are deliberately not
 * values:
 *
 *  - [isSyncing] is a supplier, because the post-broadcast re-register reads it
 *    five seconds after the broadcast and has to see the flag as it is then;
 *  - [scope] is the caller's long-lived scope, so that re-register outlives the
 *    send call itself.
 */
data class SendContext(
    val network: NetworkType,
    val walletId: String,
    /** The active wallet's lock script, or null when there is no wallet. */
    val activeScript: Script?,
    val isSyncing: () -> Boolean,
    val scope: CoroutineScope,
)

/**
 * The send path, extracted from `GatewayRepository` into the shared core
 * (M3 #5): preview, build, reserve, broadcast, retry.
 *
 * This is the money path, so the members below are the repository's bodies
 * moved rather than rewritten. The ordering, the mutex boundaries, the row
 * states and the error strings are all load-bearing and all unchanged:
 *
 *  - [sendMutex] covers fetch, reservation-filter, build, sign and the
 *    pre-broadcast row inserts, so two concurrent sends (a transfer and a DAO
 *    deposit, say) can never select the same input cells (#115, #320);
 *  - the broadcast itself happens OUTSIDE the mutex, because serializing a
 *    long-running network call would serialize every send for no benefit;
 *  - [sendTransaction] is idempotent on the pre-inserted hash, which is what
 *    lets [buildReserveAndSend] insert first and broadcast second.
 *
 * ## Session-broadcast set
 *
 * [broadcastedThisSession] holds the hashes broadcast in THIS process. Only
 * those are safe to spend as synthetic change: a synthetic change cell
 * resolves only if its creating transaction is in the light client's
 * in-memory pending pool, which is wiped on every restart. A persisted
 * `pending_broadcasts` row from a prior session is not in the pool, so feeding
 * its change into a new send makes the node fail to resolve the input and
 * reject the transaction locally, surfacing as a "Could not broadcast" error
 * that survives reboots (Alex report).
 *
 * It was a `java.util.Collections.synchronizedSet` on Android; `commonMain`
 * has no such thing, so it is a plain set behind its own small [Mutex]. That
 * mutex is taken only for the two adds after a broadcast and the one read
 * inside [resolveSpendableCells]; it is never held across a suspend point that
 * could reach [sendMutex], so the two cannot deadlock.
 */
class SendPipeline(
    private val lightClient: LightClientApi,
    private val transactionBuilder: TransactionBuilder,
    private val ledger: LedgerReader,
    private val pendingBroadcasts: PendingBroadcastStore,
    private val transactions: TransactionStore,
    private val syncEngine: SyncEngine,
    private val syncCoordinator: SyncCoordinator,
    private val uiPreferences: UiPreferences,
    private val json: Json,
    private val logger: Logger,
    private val clock: Clock = SystemClock,
    private val queryContext: CoroutineContext = Dispatchers.Default,
) {

    private val sendMutex = Mutex()

    private val sessionMutex = Mutex()
    private val broadcastedThisSession = mutableSetOf<String>()

    // ========================================
    // Preview
    // ========================================

    /**
     * Fee and change for a transfer, computed from the cells the send would
     * actually select, without signing, reserving or broadcasting anything.
     *
     * The Send review sheet quotes this (#490). Selection is smallest-first,
     * so a fragmented wallet spends many inputs and pays materially more than
     * a 1-input estimate; showing the estimate and then paying the plan is the
     * bug this closes. [prepareAndSend] re-runs the same plan against the cell
     * set it holds the mutex over and refuses to broadcast if the fee moved.
     *
     * Throws whatever the selection throws (no cells, insufficient balance);
     * the caller surfaces it instead of opening the review sheet. Returns the
     * plan rather than a `Result` so it stays stubbable in ViewModel tests —
     * MockK cannot round-trip an inline-class return through a suspend resume.
     */
    @Throws(Throwable::class)
    suspend fun previewTransfer(
        ctx: SendContext,
        fromAddress: String,
        recipients: List<RecipientOutput>,
    ): TransferPlan {
        val senderNetwork = ctx.network
        val walletId = ctx.walletId
        return sendMutex.withLock {
            val cells = resolveSpendableCells(fromAddress, senderNetwork, walletId, senderNetwork.name)
            transactionBuilder.planTransfer(recipients, cells)
        }
    }

    /**
     * The largest single transfer this wallet can fund right now: the "MAX"
     * pill on the send form.
     *
     * Priced over [resolveSpendableCells], the SAME set [prepareAndSend] will
     * select from, rather than over the raw live cells Android's
     * `setMaxAmount` reads. That is deliberate and is the one behavioural
     * difference between the platforms here: cells reserved by an in-flight
     * broadcast are not spendable, and a MAX computed over them quotes a
     * number the send then refuses. It also means the fee is estimated over
     * the real input count, which on a fragmented wallet is the difference
     * between a MAX that sends and one that fails (#321).
     */
    @Throws(Throwable::class)
    suspend fun maxSendable(ctx: SendContext, fromAddress: String): Long {
        val senderNetwork = ctx.network
        val walletId = ctx.walletId
        return sendMutex.withLock {
            val cells = resolveSpendableCells(fromAddress, senderNetwork, walletId, senderNetwork.name)
            transactionBuilder.calculateMaxSendable(cells)
        }
    }

    // ========================================
    // Send
    // ========================================

    /**
     * Plain secp256k1 transfer. [fromAddress] is the authoritative sender
     * identity (captured by the caller before this call) and is trusted over
     * live repository globals.
     */
    suspend fun prepareAndSend(
        ctx: SendContext,
        fromAddress: String,
        toAddress: String,
        amountShannons: Long,
        signer: Signer,
        /** Fee the user confirmed on the review sheet; the send aborts if the build no longer matches it (#490). */
        expectedFeeShannons: Long? = null,
    ): Result<String> = runCatching {
        buildReserveAndSend(ctx, fromAddress) { availableCells, net ->
            verifyExpectedFee(
                recipients = listOf(RecipientOutput(toAddress, amountShannons)),
                availableCells = availableCells,
                expectedFeeShannons = expectedFeeShannons,
            )
            transactionBuilder.buildTransfer(
                fromAddress = fromAddress,
                toAddress = toAddress,
                amountShannons = amountShannons,
                availableCells = availableCells,
                signer = signer,
                network = net
            )
        }
    }

    /**
     * [prepareAndSend] for a caller that cannot read a Kotlin `Result`.
     *
     * Kotlin/Native exports `Result<T>` as an opaque `Any?` with no way to
     * unwrap it, so every Swift entry point on this class answers a plain
     * value and throws instead. The same arrangement `ActivityFeed` uses for
     * the read path (#9). Nothing else differs: the work, the ordering and the
     * mutex are [prepareAndSend]'s.
     */
    @Throws(Throwable::class)
    suspend fun prepareAndSendOrThrow(
        ctx: SendContext,
        fromAddress: String,
        toAddress: String,
        amountShannons: Long,
        signer: Signer,
        expectedFeeShannons: Long?,
    ): String = prepareAndSend(
        ctx = ctx,
        fromAddress = fromAddress,
        toAddress = toAddress,
        amountShannons = amountShannons,
        signer = signer,
        expectedFeeShannons = expectedFeeShannons,
    ).getOrThrow()

    /** [retryBroadcast] for a caller that cannot read a Kotlin `Result`. */
    @Throws(Throwable::class)
    suspend fun retryBroadcastOrThrow(ctx: SendContext, txHash: String): String =
        retryBroadcast(ctx, txHash).getOrThrow()

    suspend fun prepareAndSendBulk(
        ctx: SendContext,
        fromAddress: String,
        recipients: List<RecipientOutput>,
        signer: Signer,
    ): Result<String> = runCatching {
        val txHash = buildReserveAndSend(ctx, fromAddress) { availableCells, net ->
            transactionBuilder.buildMultiTransfer(
                fromAddress = fromAddress,
                recipients = recipients,
                availableCells = availableCells,
                signer = signer,
                network = net
            )
        }
        // Remember this batch's hash so the activity list can badge it "Bulk".
        uiPreferences.addBulkTxHash(txHash)
        txHash
    }

    /**
     * Retries a FAILED `pending_broadcasts` row by re-broadcasting its
     * ORIGINAL signed bytes (#316).
     *
     * The previous flow decoded a recipient/amount and prefilled a fresh
     * send, which re-ran cell selection. Two ways that lost funds:
     *
     *  1. Double-pay. A FAILED state is a *heuristic* (still in-pool past the
     *     commit window, or fetch returned unknown N times) — not proof the
     *     network rejected the tx. The original could still be alive in a
     *     remote mempool. If the prefilled retry selected *different* inputs
     *     and the original later committed, the recipient was paid twice.
     *  2. Wrong recipient. The prefill guessed the recipient via a
     *     "smallest-capacity output" heuristic, which is the sender's own
     *     change whenever change < amount — the retry then paid the sender.
     *
     * Re-broadcasting the identical signed tx reuses the exact same inputs, so
     * the original and the retry conflict and at most one can ever commit — no
     * double-pay possible — and the recipient is whatever the original tx
     * already encodes, with no heuristic. We drop the FAILED row first so
     * [sendTransaction] re-inserts a fresh BROADCASTING row for the same hash
     * and the watchdog re-tracks it.
     */
    suspend fun retryBroadcast(ctx: SendContext, txHash: String): Result<String> = runCatching {
        val row = pendingBroadcasts.getFailedRow(txHash)
            ?: error("This transaction is too old to retry automatically. Please send a new one.")
        val tx = json.decodeFromString<Transaction>(row.signedTxJson)
        pendingBroadcasts.delete(txHash)
        transactions.deleteTransaction(txHash)
        sendTransaction(ctx, tx).getOrThrow()
    }

    suspend fun sendTransaction(
        ctx: SendContext,
        transaction: Transaction,
        /**
         * When non-null, abort if the active wallet is no longer this one —
         * a transaction signed for wallet A must never persist its pending
         * row under wallet B's id/network (#382 Tier 3 review).
         */
        expectedWalletId: String? = null,
        /**
         * Fee this send is known to pay, recorded on the pending activity row
         * so the detail sheet's "Network fee" has a value from the moment of
         * broadcast (#497). Callers that reach this path directly (DAO unlock)
         * must pass it: unlike a transfer, their fee cannot be recovered from
         * the confirmed transaction.
         */
        pendingFeeShannons: Long? = null,
    ): Result<String> = runCatching {
        logger.d(TAG, "📤 sendTransaction: building JSON")
        logger.d(TAG, "  Inputs: ${transaction.cellInputs.size}, Outputs: ${transaction.cellOutputs.size}")

        // Pre-flight checks (defense-in-depth, TransactionBuilder also validates)
        require(transaction.cellInputs.isNotEmpty()) { "Transaction has no inputs" }
        require(transaction.cellOutputs.isNotEmpty()) { "Transaction has no outputs" }
        for (output in transaction.cellOutputs) {
            val capacity = requireNotNull(output.capacity.removePrefix("0x").toLongOrNull(16)) {
                "Malformed output capacity '${output.capacity}' in transaction"
            }
            require(capacity >= TransactionBuilder.MIN_CELL_CAPACITY) {
                "Output capacity ${capacity / 100_000_000.0} CKB is below minimum 61 CKB"
            }
        }

        // Snapshot at entry — pin to whichever wallet/network the user was on.
        val walletId = ctx.walletId
        if (expectedWalletId != null && walletId != expectedWalletId) {
            throw Exception("Wallet changed before broadcast; transaction not sent")
        }
        val network = ctx.network.name
        val tipNumber = currentTipNumberOrZero()
        publishTip(tipNumber)
        val txJson = json.encodeToString(transaction)
        val txHash = transactionBuilder.computeTxHash(transaction)
        val reservedJson = json.encodeToString(
            transaction.cellInputs.map { it.previousOutput }
        )

        // Outgoing amount for the activity row. This path (DAO unlock,
        // failed-send retry) has no input capacities, so it sums the outputs
        // NOT locked to us — the recipient amount. The old code used
        // min(all outputs), which returned the CHANGE output whenever
        // change < amount sent (same bug as buildReserveAndSend; see #350).
        // Transfers via buildReserveAndSend insert their own (more precise)
        // net-debit row first and skip the insert below, so this only drives
        // standalone sends. A self-only tx (DAO unlock) sums to 0 and is
        // reclassified by the synced row.
        val ourLock = ctx.activeScript
        val outgoingOutputs = transaction.cellOutputs.map { output ->
            OutgoingOutput(
                capacityShannons = output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L,
                isOurs = ourLock != null && output.lock == ourLock,
                isTyped = output.type != null,
            )
        }
        // Positive hex per existing convention; `direction = "out"` carries sign.
        val balanceChangeHex = "0x${recipientOutgoingShannons(outgoingOutputs).toString(16)}"
        val now = clock.nowMs()

        logger.d(TAG, "📤 sendTransaction: JSON length=${txJson.length}, preHash=$txHash")

        // Critical section: pre-broadcast inserts under sendMutex.
        // Idempotent: skip insert if a row already exists for this hash
        // (prepareAndSend pre-inserts under its own mutex hold).
        sendMutex.withLock {
            val existing = pendingBroadcasts.getActive(walletId, network)
                .firstOrNull { it.txHash == txHash }
            if (existing == null) {
                pendingBroadcasts.insert(
                    PendingBroadcastRecord(
                        txHash = txHash,
                        walletId = walletId,
                        network = network,
                        signedTxJson = txJson,
                        reservedInputs = reservedJson,
                        state = "BROADCASTING",
                        submittedAtTipBlock = tipNumber,
                        nullCount = 0,
                        createdAt = now,
                        lastCheckedAt = now
                    )
                )
                transactions.insertPendingTransaction(
                    txHash = txHash,
                    network = network,
                    walletId = walletId,
                    balanceChange = balanceChangeHex,
                    direction = "out",
                    fee = "0x0",
                    feeShannons = pendingFeeShannons
                )
            } else {
                logger.d(TAG, "sendTransaction: row exists (state=${existing.state}) — skipping insert")
            }
        }

        // Bridge broadcast — outside the mutex (long-running, no need to serialize).
        val rawResult = try {
            lightClient.sendTransaction(txJson)
        } catch (e: Exception) {
            pendingBroadcasts.delete(txHash)
            transactions.deleteTransaction(txHash)
            throw e
        }

        if (rawResult == null) {
            pendingBroadcasts.delete(txHash)
            transactions.deleteTransaction(txHash)
            throw Exception("Send failed - native returned null")
        }

        // The bridge now returns the real rejection reason with a sentinel prefix
        // instead of null, so we can surface WHY a broadcast was rejected (e.g.
        // an unresolvable input from a stale pending tx) rather than blaming the
        // network. Clean up the row we inserted, same as the null path.
        if (rawResult.startsWith(BROADCAST_ERROR_PREFIX)) {
            val reason = rawResult.removePrefix(BROADCAST_ERROR_PREFIX)
            pendingBroadcasts.delete(txHash)
            transactions.deleteTransaction(txHash)
            throw Exception("Broadcast rejected: $reason")
        }

        val returnedHash = rawResult.trim('"')
        // Broadcast accepted → the tx is now in the light client's in-memory
        // pending pool this session, so its change is safe to spend as synthetic
        // change until it confirms. Record both key variants (pre-hash and the
        // returned hash) since the pending_broadcasts row may be keyed by either.
        sessionMutex.withLock {
            broadcastedThisSession.add(txHash)
            broadcastedThisSession.add(returnedHash)
        }
        if (returnedHash.lowercase() != txHash.lowercase()) {
            // Step 0 verified equality on testnet; this branch should be unreachable.
            // If it fires in production, the tx WAS broadcast under returnedHash but
            // our pre-broadcast hash derivation disagrees. Re-key both rows so cleanup
            // paths align with what the network sees.
            logger.e(TAG, "❌ Hash mismatch! pre=$txHash returned=$returnedHash — re-keying rows")
            pendingBroadcasts.delete(txHash)
            transactions.deleteTransaction(txHash)
            pendingBroadcasts.insert(
                PendingBroadcastRecord(
                    txHash = returnedHash,
                    walletId = walletId,
                    network = network,
                    signedTxJson = txJson,
                    reservedInputs = reservedJson,
                    state = "BROADCAST",
                    submittedAtTipBlock = tipNumber,
                    nullCount = 0,
                    createdAt = now,
                    lastCheckedAt = clock.nowMs()
                )
            )
            transactions.insertPendingTransaction(
                txHash = returnedHash,
                network = network,
                walletId = walletId,
                balanceChange = balanceChangeHex,
                direction = "out",
                fee = "0x0",
                feeShannons = pendingFeeShannons
            )
        } else {
            val ok = pendingBroadcasts.compareAndUpdateState(
                hash = txHash,
                expected = "BROADCASTING",
                next = "BROADCAST",
                now = clock.nowMs()
            )
            if (ok != 1) {
                logger.w(TAG, "compareAndUpdateState saw row not in BROADCASTING (race?); proceeding")
            }
        }

        logger.d(TAG, "✅ sendTransaction: returnedHash=$returnedHash")

        // After sending, nudge the light client to rescan from a few blocks back
        // so it picks up the new change output when the tx confirms. Capture the
        // sender's wallet info up front — if the user switches wallets during the
        // 5s delay, we must still re-register the script that actually sent.
        val senderScript = ctx.activeScript
        val senderWalletId = ctx.walletId
        ctx.scope.launch {
            try {
                delay(5000) // Wait a bit for tx to propagate
                // #332: on a still-catching-up wallet, re-registering at
                // tip-10 JUMPS the script forward over unscanned history —
                // silent balance/history loss. The ongoing scan will find the
                // change output anyway; only fast-path when already synced.
                if (ctx.isSyncing()) {
                    logger.d(TAG, "Skipping post-send partial re-register: wallet still catching up")
                    return@launch
                }
                val tipStr = lightClient.getTipHeader()
                if (tipStr != null && senderScript != null) {
                    val tip = json.decodeFromString<JniHeaderView>(tipStr)
                    val tipHeight = tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L
                    val rescanFrom = (tipHeight - 10).coerceAtLeast(0L)
                    logger.d(TAG, "🔄 Partial re-register from block $rescanFrom to catch change output")

                    val blockNumberHex = "0x${rescanFrom.toString(16)}"
                    // Only register lock script (not DAO type) with PARTIAL mode
                    val scriptStatuses = listOf(
                        JniScriptStatus(
                            script = senderScript,
                            scriptType = "lock",
                            blockNumber = blockNumberHex
                        )
                    )
                    // ctx.network is the send's snapshot, deliberately, and not
                    // the repository's live `currentNetwork` this used to read.
                    // A network switch persists the new choice and only then
                    // kills the process, so a live read inside this 5s window
                    // could answer the NEW network and record the sender's
                    // script against it, leaving a filter registration on a
                    // chain the wallet never sent on.
                    syncCoordinator.setScriptsAndRecord(
                        scriptStatuses,
                        listOf(senderWalletId),
                        SyncCoordinator.CMD_SET_SCRIPTS_PARTIAL,
                        ctx.network,
                    )
                }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to re-register script after send: ${e.message}")
            }
        }

        returnedHash
    }

    /**
     * Single mutex-guarded prepare-and-send shared by plain transfers and DAO
     * operations (#115, #320). Runs cell-fetch, reservation filter, build,
     * sign, and pre-broadcast persistence all inside [sendMutex] — closing the
     * read-filter-insert race that would otherwise let two concurrent sends
     * (e.g. a transfer and a DAO deposit) pick the same input cells and produce
     * conflicting transactions.
     *
     * [build] receives the reservation-filtered spendable cells (live cells
     * minus inputs reserved by in-flight broadcasts, plus synthesized
     * change-outputs of pending sends) and the snapshot network, and returns
     * the signed transaction.
     *
     * The broadcast happens AFTER the mutex is released — locking that would
     * needlessly serialize all sends. [sendTransaction] is idempotent on the
     * pre-inserted hash, so it skips the duplicate insert and just performs the
     * broadcast + post-broadcast CAS.
     */
    suspend fun buildReserveAndSend(
        ctx: SendContext,
        fromAddress: String,
        // Pending activity-row overrides for DAO ops (#433). A plain transfer
        // leaves these null and the row is a generic "out" whose balanceChange
        // is the computed net debit. A DAO withdraw/deposit passes its true
        // direction + amount so the pending row reads "Dao Withdraw 250 CKB"
        // instead of surfacing the tiny fee as a "-0.001 Sent". Fee is stored
        // separately so the detail view's fee line is populated.
        pendingDirection: String = "out",
        pendingAmountShannons: Long? = null,
        pendingFeeShannons: Long? = null,
        build: (availableCells: List<Cell>, network: NetworkType) -> Transaction
    ): String {
        // Snapshot every piece of sender state at function entry. The user can
        // switch wallet/network mid-send (rare, but possible — Settings is one
        // tap away); we must not let live reads inside the mutex retarget the
        // send to the new wallet while we persist rows under the old walletId.
        val senderNetwork = ctx.network
        val walletId = ctx.walletId
        val network = senderNetwork.name
        val tipNumber = currentTipNumberOrZero()
        publishTip(tipNumber)

        val signedTx = sendMutex.withLock {
            val filtered = resolveSpendableCells(fromAddress, senderNetwork, walletId, network)

            val signed = build(filtered, senderNetwork)

            val txHash = transactionBuilder.computeTxHash(signed)
            val txJson = json.encodeToString(signed)
            val reservedJson = json.encodeToString(signed.cellInputs.map { it.previousOutput })
            val now = clock.nowMs()

            // Outgoing amount for activity-row balanceChange. Stored as POSITIVE
            // hex per existing convention; `direction = "out"` carries the sign
            // for the UI.
            //
            // Net debit = Σ(our input capacities) − Σ(our plain-change outputs),
            // matching the confirmed-row formula. The old code used
            // min(all outputs), which returned the CHANGE output whenever
            // change < amount sent — a 150,000 CKB send displayed as
            // "-17,950.29" until the light client synced and corrected it
            // (Alex, Telegram). A typed self-output (DAO deposit cell) is
            // capacity leaving spendable, so it is not counted as change.
            val inputCapacities = signed.cellInputs.mapNotNull { input ->
                filtered.find { it.outPoint == input.previousOutput }
                    ?.capacity?.removePrefix("0x")?.toLongOrNull(16)
            }
            val outgoingOutputs = signed.cellOutputs.map { output ->
                val isOurs = runCatching {
                    AddressUtils.encode(output.lock, senderNetwork)
                }.getOrNull() == fromAddress
                OutgoingOutput(
                    capacityShannons = output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L,
                    isOurs = isOurs,
                    isTyped = output.type != null,
                )
            }
            val outgoingAmount = computeOutgoingShannons(inputCapacities, outgoingOutputs)
            val balanceChangeHex = "0x${outgoingAmount.toString(16)}"

            // Planned fee for the pending activity row (#497). DAO ops pass
            // theirs in; a plain transfer derives it from the same inputs and
            // outputs already resolved above — Σ(inputs) − Σ(all outputs),
            // change included. `mapNotNull` above drops any input not in the
            // reserved set, and computeFeeShannons refuses to score a partial
            // set, so a dropped input yields null ("Pending") not a wrong fee.
            val plannedFeeShannons = pendingFeeShannons ?: computeFeeShannons(
                resolvedInputs = inputCapacities,
                declaredInputCount = signed.cellInputs.size,
                // Parsed strictly, not through outgoingOutputs' display-oriented
                // `?: 0L`: an unparseable output would otherwise inflate the fee
                // by that output's whole capacity.
                outputCapacities = signed.cellOutputs.map {
                    it.capacity.removePrefix("0x").toLongOrNull(16)
                },
            )

            pendingBroadcasts.insert(
                PendingBroadcastRecord(
                    txHash = txHash,
                    walletId = walletId,
                    network = network,
                    signedTxJson = txJson,
                    reservedInputs = reservedJson,
                    state = "BROADCASTING",
                    submittedAtTipBlock = tipNumber,
                    nullCount = 0,
                    createdAt = now,
                    lastCheckedAt = now
                )
            )
            transactions.insertPendingTransaction(
                txHash = txHash,
                network = network,
                walletId = walletId,
                balanceChange = pendingAmountShannons?.let { "0x${it.toString(16)}" } ?: balanceChangeHex,
                direction = pendingDirection,
                fee = pendingFeeShannons?.let { "0x${it.toString(16)}" } ?: "0x0",
                feeShannons = plannedFeeShannons
            )
            signed
        }

        // sendTransaction owns the broadcast + post-broadcast CAS.
        // Its insert path is idempotent: it sees the row we just inserted
        // and skips re-insertion, then performs broadcast + state CAS.
        return sendTransaction(ctx, signedTx).getOrThrow()
    }

    // ========================================
    // Internals
    // ========================================

    /**
     * The cells a send may spend right now: live regular cells minus the ones
     * in-flight broadcasts have reserved, plus the change those broadcasts are
     * about to create.
     *
     * Extracted from [buildReserveAndSend] so [previewTransfer] plans against
     * exactly the same set the build will select from — a preview against a
     * different cell set would quote a fee the send does not pay (#490).
     * Callers hold [sendMutex].
     */
    private suspend fun resolveSpendableCells(
        fromAddress: String,
        senderNetwork: NetworkType,
        walletId: String,
        network: String,
    ): List<Cell> {
        // getCells(fromAddress) decodes the address to a script — honors the
        // snapshot rather than reading the active wallet live. It already
        // excludes typed (DAO/token) cells, so this is regular spendable CKB.
        val cellsResult = getCells(fromAddress).getOrThrow()
        val pending = pendingBroadcasts.getActive(walletId, network)
        val reserved: Set<OutPoint> = pending
            .flatMap { json.decodeFromString<List<OutPoint>>(it.reservedInputs) }
            .toSet()
        val liveFiltered = cellsResult.items.filter { it.outPoint !in reserved }

        // Synthesize predicted change-output cells from in-flight broadcasts.
        // Without this, rapid sequential sends exhaust live cells before the
        // light client has synced the change outputs of prior sends — the
        // observed "Not enough funds available" failure mode.
        // We include each output of every active pending tx whose lock script
        // matches the sender's lock (= change output going back to us).
        // If a pending tx ultimately FAILs, downstream txs that consumed its
        // synthetic change will also fail and the watchdog times them out.
        //
        // ONLY session-broadcast rows: a synthetic input resolves only if
        // its creating tx is in the light client's in-memory pending pool,
        // which is wiped on restart. Persisted rows from a prior session are
        // not in the pool, so their change would be unresolvable and reject
        // every send after a reboot (Alex report). The reservation filter
        // above still uses ALL pending rows so reserved inputs are never
        // double-spent.
        val session = sessionMutex.withLock { broadcastedThisSession.toSet() }
        val pendingChange: List<Cell> = pending
            .filter { it.txHash in session }
            .flatMap { row ->
                val pendingTx = try {
                    json.decodeFromString<Transaction>(row.signedTxJson)
                } catch (e: Exception) {
                    return@flatMap emptyList<Cell>()
                }
                pendingTx.cellOutputs.mapIndexedNotNull { idx, output ->
                    val outAddr = try {
                        AddressUtils.encode(output.lock, senderNetwork)
                    } catch (e: Exception) {
                        return@mapIndexedNotNull null
                    }
                    if (outAddr != fromAddress) return@mapIndexedNotNull null
                    Cell(
                        outPoint = OutPoint(row.txHash, "0x${idx.toString(16)}"),
                        capacity = output.capacity,
                        blockNumber = "0x0", // synthetic — not on chain yet
                        lock = output.lock,
                        type = output.type,
                        data = "0x"
                    )
                }
            }
        // Dedup by outpoint, preferring the real (on-chain) cell: once a
        // pending tx confirms, its change appears in both liveFiltered and
        // pendingChange for the brief window before the watchdog clears the
        // row. Selecting the same outpoint twice would build a tx with a
        // duplicate input and fail. liveFiltered is first, so distinctBy
        // keeps the real cell.
        val filtered = (liveFiltered + pendingChange)
            .distinctBy { "${it.outPoint.txHash}:${it.outPoint.index}" }
        logger.d(
            TAG,
            "resolveSpendableCells: ${cellsResult.items.size} live, ${reserved.size} reserved, " +
                "${pendingChange.size} synthetic-change, ${filtered.size} available"
        )
        return filtered
    }

    /**
     * Guards a confirmed send against a fee that moved between the review and
     * the broadcast (a cell confirmed, a pending tx resolved). Refusing is the
     * safe branch: the user re-opens the sheet and confirms the new number
     * rather than silently paying a fee they never saw.
     */
    private fun verifyExpectedFee(
        recipients: List<RecipientOutput>,
        availableCells: List<Cell>,
        expectedFeeShannons: Long?,
    ) {
        if (expectedFeeShannons == null) return
        val actualFee = transactionBuilder.planTransfer(recipients, availableCells).feeShannons
        if (actualFee != expectedFeeShannons) {
            throw IllegalStateException(
                "Fee changed since you reviewed this transaction " +
                    "(expected $expectedFeeShannons, actual $actualFee shannons). " +
                    "Nothing was sent, please review and confirm again."
            )
        }
    }

    /** Live spendable cells for an explicit sender address, as the repository read them. */
    private suspend fun getCells(address: String): Result<CellsResponse> = runCatching {
        val script = AddressUtils.parseAddress(address)
            ?: throw Exception("Invalid address: $address")
        ledger.getCells(script, 100, null).getOrThrow()
    }

    /**
     * Parsed tip block number, or 0L if the light client hasn't reported a
     * header yet (cold start) or the header failed to decode (transient bridge
     * error). Hops to [queryContext] because `LightClientReadOnly`, which this
     * replaces, forced `Dispatchers.IO` for the blocking bridge call.
     */
    private suspend fun currentTipNumberOrZero(): Long = withContext(queryContext) {
        try {
            val tipStr = lightClient.getTipHeader() ?: return@withContext 0L
            val tip = json.decodeFromString<JniHeaderView>(tipStr)
            tip.number.removePrefix("0x").toLong(16)
        } catch (e: Exception) {
            logger.w(TAG, "currentTipNumberOrZero failed: ${e.message}")
            0L
        }
    }

    private fun publishTip(n: Long) = syncEngine.publishTip(n)

    companion object {
        /**
         * The send path logged under "GatewayRepository" before the extraction.
         * The message texts are unchanged, so a logcat filter on the text still
         * matches; a filter on the tag has to be pointed here.
         */
        private const val TAG = "SendPipeline"

        // Matches SEND_ERROR_PREFIX in the Rust bridge (query.rs): a rejected
        // broadcast answers "__SEND_ERROR__:<reason>" instead of null.
        const val BROADCAST_ERROR_PREFIX = "__SEND_ERROR__:"
    }
}
