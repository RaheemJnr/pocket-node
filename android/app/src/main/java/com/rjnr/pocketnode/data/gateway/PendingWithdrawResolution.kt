package com.rjnr.pocketnode.data.gateway

/** Outcome of reconciling a persisted in-flight DAO withdraw marker (#347). */
enum class PendingWithdrawResolution {
    /** Keep the marker; overlay the deposit's status as WITHDRAWING ("Confirming…"). */
    OVERLAY,

    /** Withdraw committed (deposit cell consumed) — retire the marker. */
    CLEAR_CONFIRMED,

    /** Withdraw failed — retire the marker so the deposit is withdrawable again. */
    CLEAR_FAILED,
}

/**
 * Decide a pending withdraw marker's fate from two facts:
 *  - [depositStillDeposited]: is the deposit cell still DEPOSITED in the live
 *    light-client scan? (false = consumed by a committed withdraw.)
 *  - [withdrawTxStatus]: the withdraw tx's status in the local cache
 *    ("PENDING"/"CONFIRMED"/"FAILED"/null), maintained by BroadcastWatchdog.
 *
 * The deposit disappearing is the authoritative success signal — it only
 * happens once the spend is on-chain and indexed. A FAILED tx while the
 * deposit is still present is the authoritative failure signal. Everything
 * else means the withdraw is still in flight.
 */
fun resolvePendingWithdraw(
    depositStillDeposited: Boolean,
    withdrawTxStatus: String?,
): PendingWithdrawResolution = when {
    !depositStillDeposited -> PendingWithdrawResolution.CLEAR_CONFIRMED
    withdrawTxStatus == "FAILED" -> PendingWithdrawResolution.CLEAR_FAILED
    else -> PendingWithdrawResolution.OVERLAY
}
