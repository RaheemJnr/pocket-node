package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Where one send has got to. The Android `TransactionState`, shared. */
enum class SendState {
    /** Nothing in flight. */
    IDLE,

    /** Being built, signed or broadcast. */
    SENDING,

    /** Broadcast, waiting for the chain. */
    PENDING,

    /** Seen in a proposal. */
    PROPOSED,

    /** Committed, with at least one confirmation. */
    CONFIRMED,

    /** Terminal failure. */
    FAILED,

    /**
     * Terminal, outcome unknown: the poll ran out of attempts without an
     * answer either way. The send is over as far as the status sheet is
     * concerned (it can be closed), but the transaction is not: its pending
     * broadcast row stays in Activity, where the broadcast watchdog and the
     * startup reconciler keep resolving it, so a later confirmation still
     * shows up there.
     */
    TIMED_OUT,
}

/**
 * Everything the send status sheet draws, as one value.
 *
 * A single object rather than four flows so the sheet can never render a state
 * from one tick beside a confirmation count from the next.
 */
data class SendProgress(
    val state: SendState = SendState.IDLE,
    val statusMessage: String = "",
    val confirmations: Int = 0,
    /** Null until a broadcast has returned a hash. */
    val txHash: String? = null,
)

/**
 * Inclusion evidence for a send, scoped to the hash returned by broadcast.
 * The ledger/native query supplies the committed status and inclusion block;
 * elapsed time, balance movement and peer availability cannot substitute for it.
 * Keep this separate from ledger/DAO proof policies.
 */
fun TransactionStatusResponse.hasSendInclusionFor(submittedHash: String): Boolean =
    txHash == submittedHash && isConfirmed() &&
        blockHash?.let { hash ->
            hash.length == 66 && hash.startsWith("0x") &&
                hash.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        } == true

/**
 * Post-broadcast status for the submitted hash. Unknown or unavailable reads
 * remain unresolved until the bounded poll ends in [SendState.TIMED_OUT].
 * Balance watching only refreshes presentation after evidenced inclusion.
 * Nothing here builds, signs or broadcasts a transaction; [start] can also
 * resume a read-only status check for a retained hash.
 */
class SendStatusPoller(
    private val ledger: LedgerReader,
    private val balanceChangedSinceStart: () -> Boolean,
    private val logger: Logger = NoopLogger,
    private val clock: Clock = SystemClock,
    scopeContext: CoroutineContext = Dispatchers.Default,
) {

    private val _state = MutableStateFlow(SendProgress())

    /** What the status sheet renders. */
    val state: StateFlow<SendProgress> = _state.asStateFlow()

    /**
     * The scope [start] uses when the caller has none of its own.
     *
     * Swift cannot construct a `CoroutineScope`, the same wall
     * [SingleWalletSyncService][com.rjnr.pocketnode.data.sync.SingleWalletSyncService]
     * ran into, so the object that outlives the screen owns one. `SupervisorJob`
     * so a failed poll cannot take the next send's down with it.
     */
    private val ownScope = CoroutineScope(SupervisorJob() + scopeContext)

    private var job: Job? = null

    /**
     * Bumped on every [start]. A poll that finishes after a newer one has
     * begun carries the older value and must not clear [job], which by then
     * belongs to the newer poll: doing so would leave the running loop
     * unstoppable by [stop]. Same arrangement as
     * `SingleWalletSyncService.registrationEpoch`.
     */
    private var generation = 0

    // ========================================
    // Pre-broadcast markers
    // ========================================

    /** Back to nothing in flight: the sheet is dismissed. */
    fun reset() {
        stop()
        _state.value = SendProgress()
    }

    /**
     * Both markers stop whatever poll is running first. A new send must not be
     * narrated by the previous one's loop, which would otherwise go on
     * overwriting these messages with the old transaction's status.
     */
    fun markBuilding() {
        stop()
        _state.value = SendProgress(state = SendState.SENDING, statusMessage = BUILDING)
    }

    fun markBroadcasting() {
        stop()
        _state.value = _state.value.copy(state = SendState.SENDING, statusMessage = BROADCASTING)
    }

    /**
     * A send that never reached the chain. The hash is kept when there is one,
     * since a broadcast that was accepted and then failed still has a hash
     * worth looking up.
     */
    fun markFailed() {
        stop()
        _state.value = _state.value.copy(state = SendState.FAILED, statusMessage = FAILED)
    }

    // ========================================
    // Poll
    // ========================================

    /**
     * Start watching [txHash]. Replaces any poll already running, so a second
     * send cannot be reported under the first one's hash.
     *
     * [scope] is the caller's: on iOS the one `SendService` owns for the life
     * of the screen, so a poll survives a sheet redraw and dies with the
     * service rather than with a view.
     */
    fun start(txHash: String, scope: CoroutineScope) {
        stop()
        val epoch = ++generation
        _state.value = SendProgress(
            state = SendState.PENDING,
            statusMessage = SUBMITTED,
            confirmations = 0,
            txHash = txHash,
        )
        job = scope.launch { poll(txHash, epoch) }
    }

    /**
     * Start watching [txHash] in this object's own scope.
     *
     * The Swift entry point: no `CoroutineScope` to hand in, and the poll
     * lives exactly as long as the object does, which for `SendService` is
     * the life of the app.
     */
    fun start(txHash: String) = start(txHash, ownScope)

    /** Stop polling. The last published progress stays on screen. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
    }

    /** Stop polling for good. The object is unusable afterwards. */
    fun close() {
        stop()
        ownScope.cancel()
    }

    private suspend fun poll(txHash: String, epoch: Int) {
        val startedAt = clock.nowMs()
        var attempts = 0

        while (attempts < MAX_POLLING_ATTEMPTS) {
            delay(POLLING_INTERVAL_MS)
            if (!isCurrent(epoch)) return
            attempts++
            val status = readStatus(txHash)
            if (!isCurrent(epoch)) return
            // A failed read or response for a different hash is not evidence.
            if (status == null || status.txHash != txHash) continue

            if (status.status == "rejected") {
                _state.value = _state.value.copy(
                    state = SendState.FAILED, confirmations = 0, statusMessage = REJECTED,
                )
                finish(epoch, startedAt, attempts)
                return
            }
            apply(status, txHash)
            if (status.hasSendInclusionFor(txHash) && (status.confirmations ?: 0) >= REQUIRED_CONFIRMATIONS) {
                awaitBalanceChange()
                if (!isCurrent(epoch)) return
                _state.value = _state.value.copy(statusMessage = fullyConfirmed(status.confirmations ?: 0))
                finish(epoch, startedAt, attempts)
                return
            }
        }

        if (!isCurrent(epoch)) return
        if (_state.value.state != SendState.CONFIRMED) {
            _state.value = _state.value.copy(state = SendState.TIMED_OUT, confirmations = 0, statusMessage = TIMED_OUT)
        }
        finish(epoch, startedAt, attempts)
    }

    private suspend fun isCurrent(epoch: Int): Boolean {
        currentCoroutineContext().ensureActive()
        return epoch == generation
    }

    private suspend fun readStatus(txHash: String): TransactionStatusResponse? = try {
        ledger.getTransactionStatus(txHash).getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        logger.w(TAG, "poll failed: ${e.message}")
        null
    }

    private fun apply(status: TransactionStatusResponse, txHash: String) {
        val next = when {
            status.hasSendInclusionFor(txHash) -> SendState.CONFIRMED
            status.status == "proposed" -> SendState.PROPOSED
            else -> SendState.PENDING
        }
        val confirmations = if (next == SendState.CONFIRMED) status.confirmations ?: 0 else 0
        val message = when (next) {
            SendState.PENDING -> if (status.isUnknown()) AWAITING_NETWORK else PENDING
            SendState.PROPOSED -> PROPOSED
            SendState.CONFIRMED -> when {
                confirmations >= REQUIRED_CONFIRMATIONS -> fullyConfirmed(confirmations)
                confirmations == 1 -> "1 confirmation (waiting for ${REQUIRED_CONFIRMATIONS - 1} more)..."
                else -> "$confirmations confirmations (waiting for ${REQUIRED_CONFIRMATIONS - confirmations} more)..."
            }
            else -> PROCESSING
        }
        _state.value = _state.value.copy(
            state = next,
            confirmations = confirmations,
            statusMessage = message,
        )
    }

    /**
     * Wait for the wallet's balance to move, up to [MAX_BALANCE_ATTEMPTS]
     * ticks.
     *
     * The light client has to sync the block holding the change output before
     * the new balance is visible, which is why this exists at all: without it
     * the sheet says "confirmed" over a balance that is still the old one.
     *
     * Inclusion was already established before this presentation wait. A
     * balance that never moves cannot revoke it; a balance that moves cannot
     * establish it. The platform owns refreshes; this callback only reads.
     */
    private suspend fun awaitBalanceChange() {
        var attempts = 0
        while (attempts < MAX_BALANCE_ATTEMPTS) {
            delay(POLLING_INTERVAL_MS)
            attempts++
            if (balanceChangedSinceStart()) return
        }
        logger.w(TAG, "balance did not move within $MAX_BALANCE_ATTEMPTS checks")
    }

    private fun finish(epoch: Int, startedAtMs: Long, attempts: Int) {
        // Only if [job] is still this poll's. A newer [start] has already
        // replaced it, and clearing it here would strand that newer loop.
        if (epoch == generation) job = null
        logger.d(TAG, "poll ended after $attempts attempts, ${clock.nowMs() - startedAtMs}ms")
    }

    private fun fullyConfirmed(confirmations: Int) = "Fully confirmed with $confirmations confirmations"

    companion object {
        private const val TAG = "SendStatusPoller"

        const val POLLING_INTERVAL_MS = 3_000L

        /** ~6 minutes at [POLLING_INTERVAL_MS]. */
        const val MAX_POLLING_ATTEMPTS = 120

        /** Fully confirmed at three. */
        const val REQUIRED_CONFIRMATIONS = 3

        /** ~90 seconds of balance watching at [POLLING_INTERVAL_MS]. */
        const val MAX_BALANCE_ATTEMPTS = 30

        // The strings, in one place. Android appends a check-mark glyph to
        // three of these; iOS draws its own success icon and does not.
        const val BUILDING = "Building transaction..."
        const val BROADCASTING = "Broadcasting transaction..."
        const val SUBMITTED = "Transaction submitted. Waiting for confirmation..."
        const val AWAITING_NETWORK = "Transaction broadcast. Waiting for network confirmation..."
        const val PENDING = "Transaction pending..."
        const val PROPOSED = "Transaction in proposal stage..."
        const val PROCESSING = "Processing..."
        const val FAILED = "Transaction failed"
        const val REJECTED = "Transaction rejected by the node. Check Activity for details."
        const val TIMED_OUT = "We could not confirm this transaction yet. Check Activity for its status."
    }
}
