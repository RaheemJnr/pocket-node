package com.rjnr.pocketnode.data.history

import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord

/**
 * The three tabs the activity list offers, and the directions each one keeps.
 *
 * The sets are the Android `TransactionDao` paging queries', verbatim: a DAO
 * unlock returns CKB so it reads as received, and everything this wallet can
 * originate (including the two DAO operations that move money out of the
 * spendable balance) reads as sent.
 */
enum class ActivityFilter(val directions: List<String>) {
    /** No direction filter at all. */
    ALL(emptyList()),
    RECEIVED(listOf("in", "dao_unlock")),
    SENT(listOf("out", "self", "dao_deposit", "dao_withdraw")),
}

/**
 * Broadcast-lifecycle state a transaction row is *displayed* in.
 *
 * The `transactions` table only stores PENDING / CONFIRMED / FAILED. The finer
 * "we are still handing the bytes to the node" step lives in
 * `pending_broadcasts` (BROADCASTING -> BROADCAST -> CONFIRMED | FAILED), so
 * the display state is a join of the two: the ledger row says what happened,
 * the broadcast row says how far along an in-flight send is.
 *
 * Four states rather than the six a broadcast actually passes through: the
 * wallet has no confirmation-depth threshold (a transaction is final for
 * display purposes the moment it lands in a block), and "broadcast" is not
 * observable separately from "pending in the mempool" because the light client
 * reports both as in pool. Surfacing states the data cannot distinguish would
 * be worse than surfacing four honest ones. Mirrors the Android
 * `TxDisplayState`.
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

/** Why a transaction is FAILED. The platform renders each case as copy. */
enum class TxFailureReason {
    /** No broadcast row survives, so how it failed is not recoverable. */
    UNKNOWN,

    /** The network stopped reporting it: it never made it into a block. */
    DROPPED,

    /** It waited out the pool timeout, usually because an input was already spent. */
    REJECTED,
}

/** The unit an elapsed duration is rendered in. */
enum class ElapsedUnit { UNDER_MINUTE, MINUTES, HOURS, DAYS }

/**
 * A coarse relative duration: the number and its unit, with the string left to
 * the platform ("<1 min", "2 min", "3 hr", "2 d").
 *
 * [value] is null only for [ElapsedUnit.UNDER_MINUTE], which has no number.
 */
data class ElapsedBucket(val unit: ElapsedUnit, val value: Int? = null)

/**
 * One row of the activity list: the ledger record, the broadcast row that goes
 * with it if there is one, and everything derived from the pair.
 *
 * Deliberately free of UI strings. The platform turns [displayState],
 * [failureReason] and [elapsed] into words; what is decided here is which of
 * them applies, which is the part worth testing once rather than twice.
 */
data class ActivityItem(
    val record: TransactionRecord,
    val broadcast: PendingBroadcastRecord?,
    val isBulk: Boolean,
    val displayState: TxDisplayState,
    /**
     * Where the in-flight clock starts, or null when nothing usable is
     * recorded. See [pendingSince].
     */
    val pendingSinceMs: Long?,
    /**
     * How long it has been in flight as of the moment the page was read, or
     * null when the row is not in flight. The platform re-derives this from
     * [pendingSinceMs] on its own ticker; this is what the first frame draws
     * so a row does not show a blank badge until the first tick.
     */
    val elapsed: ElapsedBucket?,
    /** Set only when [displayState] is [TxDisplayState.FAILED]. */
    val failureReason: TxFailureReason?,
) {
    /** Whether an elapsed badge belongs on this row. */
    val isInFlight: Boolean get() = showsElapsed(displayState)
}

/**
 * The threshold `BroadcastWatchdog` gives up at. Duplicated from the app rather
 * than imported, exactly as the Android `TransactionStatusUi` duplicates it.
 */
const val BROADCAST_NULL_THRESHOLD = 3

/** Elapsed time is only meaningful while a transaction is still in flight. */
fun showsElapsed(state: TxDisplayState): Boolean =
    state == TxDisplayState.BROADCASTING || state == TxDisplayState.PENDING

/**
 * Resolves the display state for a ledger row.
 *
 * The precedence is the Android `TransactionStatusUi.displayState`'s:
 *
 *  1. a terminal FAILED on EITHER table wins, because the watchdog writes the
 *     ledger row first and a half-applied pair must still read as failed;
 *  2. any confirmation, or a CONFIRMED ledger row, is confirmed;
 *  3. BROADCASTING on the broadcast row is the only thing that distinguishes
 *     "still sending" from "waiting";
 *  4. everything else, a BROADCAST broadcast row included, is PENDING.
 */
fun displayStateOf(record: TransactionRecord, broadcast: PendingBroadcastRecord?): TxDisplayState =
    when {
        record.status == "FAILED" || broadcast?.state == "FAILED" -> TxDisplayState.FAILED
        record.confirmations > 0 || record.status == "CONFIRMED" -> TxDisplayState.CONFIRMED
        broadcast?.state == "BROADCASTING" -> TxDisplayState.BROADCASTING
        else -> TxDisplayState.PENDING
    }

/**
 * Instant the in-flight clock starts from: the broadcast row's `createdAt` when
 * there is one (the moment the transaction was actually handed to the node),
 * falling back to the ledger row's timestamp. Null when neither is usable, so
 * callers render the badge without an elapsed suffix rather than inventing
 * "0 min".
 */
fun pendingSince(record: TransactionRecord, broadcast: PendingBroadcastRecord?): Long? {
    val candidate = broadcast?.createdAt?.takeIf { it > 0L } ?: record.timestamp
    return candidate.takeIf { it > 0L }
}

/**
 * Coarse relative duration, bucketed.
 *
 * Coarse on purpose. The badge re-renders on a slow ticker, so a
 * seconds-precision label would be visibly stale most of the time, and a
 * pending CKB transaction that is interesting to the user is interesting at
 * minute granularity. Negative input (the device clock moved backwards) clamps
 * to the under-a-minute bucket instead of rendering a negative number.
 */
fun elapsedBucket(elapsedMillis: Long): ElapsedBucket {
    val millis = elapsedMillis.coerceAtLeast(0L)
    val minutes = millis / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> ElapsedBucket(ElapsedUnit.UNDER_MINUTE)
        minutes < 60L -> ElapsedBucket(ElapsedUnit.MINUTES, minutes.toInt())
        hours < 24L -> ElapsedBucket(ElapsedUnit.HOURS, hours.toInt())
        else -> ElapsedBucket(ElapsedUnit.DAYS, days.toInt())
    }
}

/**
 * Why a transaction is FAILED, inferred from the broadcast row the watchdog
 * left behind.
 *
 * The watchdog has exactly two routes to FAILED and they are distinguishable by
 * `nullCount`: the not-found route increments it past [BROADCAST_NULL_THRESHOLD]
 * before giving up, while the still-in-pool route resets it to 0 on every
 * healthy check. A row whose broadcast record has already been cleared (a
 * retry, or a FAILED row from before the watchdog shipped) falls back to
 * [TxFailureReason.UNKNOWN].
 */
fun failureReasonOf(broadcast: PendingBroadcastRecord?): TxFailureReason = when {
    broadcast == null -> TxFailureReason.UNKNOWN
    broadcast.nullCount >= BROADCAST_NULL_THRESHOLD -> TxFailureReason.DROPPED
    else -> TxFailureReason.REJECTED
}
