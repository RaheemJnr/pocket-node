package com.rjnr.pocketnode.ui.transaction

import androidx.annotation.StringRes
import com.rjnr.pocketnode.R

/**
 * Broadcast-lifecycle state a transaction row is *displayed* in (#432).
 *
 * The `transactions` table only stores PENDING / CONFIRMED / FAILED. The finer
 * "we are still handing the bytes to the node" step lives in `pending_broadcasts`
 * (BROADCASTING -> BROADCAST -> CONFIRMED | FAILED), so the display state is a
 * join of the two: the ledger row says what happened, the broadcast row says how
 * far along an in-flight send is.
 *
 * Deliberately *not* a 1:1 copy of the issue's six-step flow: the wallet has no
 * confirmation-depth threshold (a tx is final for display purposes the moment it
 * lands in a block), and "Broadcasted" is not observable separately from
 * "Pending in mempool" — the light client reports both as "in pool". Surfacing
 * states the data cannot actually distinguish would be worse than surfacing four
 * honest ones.
 */
enum class TxDisplayState {
    /** Signed bytes handed to the node; no acknowledgement yet. */
    BROADCASTING,

    /** Accepted by the network, sitting in the pool, waiting to be committed. */
    PENDING,

    /** Committed in a block. */
    CONFIRMED,

    /** Terminal: rejected, dropped, or never seen on chain. */
    FAILED,
}

/**
 * The slice of a `pending_broadcasts` row the UI needs. Kept as a UI-layer type
 * (not the Room entity) so the mapping below is testable without a database and
 * so the screens never see `signedTxJson`.
 */
data class BroadcastInfo(
    /** BROADCASTING | BROADCAST | CONFIRMED | FAILED. */
    val state: String,
    val nullCount: Int,
    val createdAt: Long,
)

/** A relative-duration label: a string resource plus its optional numeric arg. */
data class ElapsedLabel(
    @StringRes val res: Int,
    val value: Int? = null,
)

/**
 * Pure state-to-copy mapping for transaction rows and the detail sheet.
 *
 * Everything here is deterministic and Android-free (bar generated `R` ints) so
 * the label/elapsed rules are unit-tested rather than eyeballed in a screenshot.
 */
object TransactionStatusUi {

    /** Elapsed time is only meaningful while a transaction is still in flight. */
    fun showsElapsed(state: TxDisplayState): Boolean =
        state == TxDisplayState.BROADCASTING || state == TxDisplayState.PENDING

    /**
     * Resolves the display state for a ledger row.
     *
     * @param status `transactions.status` ("PENDING" / "CONFIRMED" / "FAILED").
     * @param confirmations confirmation count from the ledger row.
     * @param broadcast matching `pending_broadcasts` row, if one is still around.
     */
    fun displayState(
        status: String?,
        confirmations: Int,
        broadcast: BroadcastInfo?,
    ): TxDisplayState = when {
        // A terminal FAILED on either table wins: the watchdog writes the
        // ledger row first, so a half-applied pair still reads as failed.
        status == "FAILED" || broadcast?.state == "FAILED" -> TxDisplayState.FAILED
        confirmations > 0 || status == "CONFIRMED" -> TxDisplayState.CONFIRMED
        broadcast?.state == "BROADCASTING" -> TxDisplayState.BROADCASTING
        else -> TxDisplayState.PENDING
    }

    @StringRes
    fun statusLabelRes(state: TxDisplayState): Int = when (state) {
        TxDisplayState.BROADCASTING -> R.string.tx_status_broadcasting
        TxDisplayState.PENDING -> R.string.tx_status_pending
        TxDisplayState.CONFIRMED -> R.string.tx_status_confirmed
        TxDisplayState.FAILED -> R.string.tx_status_failed
    }

    /**
     * Instant the in-flight clock starts from: the broadcast row's `createdAt`
     * when we have one (the moment we actually handed the tx to the node),
     * falling back to the ledger row's timestamp. Returns null when neither is
     * usable, so callers render the badge without an elapsed suffix rather than
     * inventing "0 min".
     */
    fun pendingSince(rowTimestampMillis: Long, broadcast: BroadcastInfo?): Long? {
        val candidate = broadcast?.createdAt?.takeIf { it > 0L } ?: rowTimestampMillis
        return candidate.takeIf { it > 0L }
    }

    /**
     * Coarse relative duration: "<1 min", "2 min", "3 hr", "2 d".
     *
     * Coarse on purpose. The badge re-renders on a slow ticker, so a
     * seconds-precision label would be visibly stale most of the time; and a
     * pending CKB transaction that is interesting to the user is interesting at
     * minute granularity. Negative input (device clock moved backwards) clamps
     * to the under-a-minute bucket instead of rendering "-3 min".
     */
    fun formatElapsed(elapsedMillis: Long): ElapsedLabel {
        val millis = elapsedMillis.coerceAtLeast(0L)
        val minutes = millis / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> ElapsedLabel(R.string.tx_elapsed_under_minute)
            minutes < 60L -> ElapsedLabel(R.string.tx_elapsed_minutes, minutes.toInt())
            hours < 24L -> ElapsedLabel(R.string.tx_elapsed_hours, hours.toInt())
            else -> ElapsedLabel(R.string.tx_elapsed_days, days.toInt())
        }
    }

    /**
     * Why a transaction is FAILED, inferred from the broadcast row the watchdog
     * left behind.
     *
     * `BroadcastWatchdog` has exactly two routes to FAILED and they are
     * distinguishable by `nullCount`: the not-found route increments it past
     * [NULL_THRESHOLD] before giving up, while the still-in-pool route resets it
     * to 0 on every healthy check. Rows whose broadcast record has already been
     * cleared (a retry, or a FAILED row from before this shipped) fall back to
     * the generic reason.
     */
    @StringRes
    fun failureReasonRes(broadcast: BroadcastInfo?): Int = when {
        broadcast == null -> R.string.tx_failed_reason_unknown
        broadcast.nullCount >= NULL_THRESHOLD -> R.string.tx_failed_reason_dropped
        else -> R.string.tx_failed_reason_rejected
    }

    /**
     * Mirrors `BroadcastWatchdog.NULL_THRESHOLD`. Duplicated rather than
     * imported so this UI-layer mapping does not depend on the sync package;
     * `TransactionStatusUiTest` asserts the two stay equal.
     */
    const val NULL_THRESHOLD = 3
}
