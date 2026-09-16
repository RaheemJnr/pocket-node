package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
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
 * The post-broadcast status machine, lifted out of Android's
 * `SendViewModel.startPollingTransactionStatus` (M3 #8).
 *
 * It is the whole of the send screen's status vocabulary: the pre-broadcast
 * markers ([markBuilding], [markBroadcasting], [markFailed]) as well as the
 * poll, so that every string a user reads during a send has exactly one
 * definition on both platforms.
 *
 * ## Why the loop looks the way it does
 *
 * A light client is not a node with the whole chain. Three of the branches
 * below exist only because of that:
 *
 *  - an `unknown` answer usually means the transaction is in the NETWORK's
 *    mempool and this client has not synced the block yet, so it is reported
 *    as pending rather than as a failure;
 *  - after [UNKNOWN_CONFIRM_THRESHOLD] consecutive unknowns the transaction
 *    has almost certainly landed and the client is simply behind, so the
 *    balance is watched instead of the status, and a balance that moves is
 *    taken as the confirmation;
 *  - running out of attempts with more than [TIMEOUT_UNKNOWN_THRESHOLD]
 *    unknowns is read the same way, while running out with a handful is
 *    reported honestly as "may still confirm" rather than as either outcome.
 *
 * ## The balance callback
 *
 * [balanceChangedSinceStart] answers one question and causes nothing: has the
 * wallet's balance moved since this send began. It is a plain function rather
 * than a `suspend` one because Swift cannot hand Kotlin a suspending lambda,
 * and it deliberately does not ask for a fresh reading: the platform owns
 * that. Android refreshes inside its own poll loop and iOS runs a tick beside
 * this one, because `refreshBalance` there is main-actor isolated and this
 * runs on a Kotlin thread. Naming it for what it does is the point; the
 * previous name promised a refresh no implementation performed.
 *
 * Nothing here touches a key, a signature or a transaction body: this only
 * ever reads a status and publishes a string.
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
        var consecutiveUnknowns = 0

        while (attempts < MAX_POLLING_ATTEMPTS) {
            delay(POLLING_INTERVAL_MS)
            attempts++

            val status = readStatus(txHash)
            // A read that threw is not an answer. Android keeps polling
            // through these rather than failing the send on a transient
            // bridge error, and so does this.
            if (status == null) continue

            if (status.isUnknown()) {
                consecutiveUnknowns++
                _state.value = _state.value.copy(
                    state = SendState.PENDING,
                    statusMessage = AWAITING_NETWORK,
                )
                if (consecutiveUnknowns > UNKNOWN_CONFIRM_THRESHOLD) {
                    logger.d(TAG, "$consecutiveUnknowns consecutive unknowns; treating as confirmed")
                    awaitBalanceChange()
                    _state.value = _state.value.copy(
                        state = SendState.CONFIRMED,
                        confirmations = 1,
                        statusMessage = CONFIRMED,
                    )
                    finish(epoch, startedAt, attempts)
                    return
                }
                continue
            }

            consecutiveUnknowns = 0
            apply(status)

            if (status.isConfirmed() && (status.confirmations ?: 0) >= REQUIRED_CONFIRMATIONS) {
                awaitBalanceChange()
                _state.value = _state.value.copy(
                    statusMessage = fullyConfirmed(status.confirmations ?: 0),
                )
                finish(epoch, startedAt, attempts)
                return
            }
        }

        // Out of attempts. Mostly-unknown means the client is behind rather
        // than the transaction being lost, which is the same reading the
        // in-loop branch above takes.
        if (consecutiveUnknowns > TIMEOUT_UNKNOWN_THRESHOLD) {
            awaitBalanceChange()
            _state.value = _state.value.copy(
                state = SendState.CONFIRMED,
                statusMessage = SENT_SUCCESSFULLY,
            )
        } else {
            _state.value = _state.value.copy(statusMessage = TIMED_OUT)
        }
        finish(epoch, startedAt, attempts)
    }

    private suspend fun readStatus(txHash: String): TransactionStatusResponse? = try {
        ledger.getTransactionStatus(txHash).getOrNull()
    } catch (e: Exception) {
        logger.w(TAG, "poll failed: ${e.message}")
        null
    }

    /** The Android `updateTransactionStatus`, unchanged. */
    private fun apply(status: TransactionStatusResponse) {
        val next = when {
            status.isConfirmed() -> SendState.CONFIRMED
            status.status == "proposed" -> SendState.PROPOSED
            // An unknown status reads as pending here too; it is only the
            // consecutive-unknown COUNT that means anything more.
            else -> SendState.PENDING
        }
        val confirmations = status.confirmations ?: 0
        val message = when (next) {
            SendState.PENDING -> PENDING
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
     * Running out of ticks is not an error and is not reported as one. The
     * balance will land whenever the block does, and the caller's next line is
     * the confirmation the user was waiting for either way.
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

        /** Consecutive unknowns after which the transaction is taken as landed. */
        const val UNKNOWN_CONFIRM_THRESHOLD = 20

        /** Unknowns at timeout above which the same reading is taken. */
        const val TIMEOUT_UNKNOWN_THRESHOLD = 10

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
        const val CONFIRMED = "Transaction confirmed"
        const val SENT_SUCCESSFULLY = "Transaction sent successfully"
        const val FAILED = "Transaction failed"
        const val TIMED_OUT = "Status check timed out. Transaction may still confirm."
    }
}
