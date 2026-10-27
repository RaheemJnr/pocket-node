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
 * An empty scan can be a revalidation or a shallow horizon, not a spend.
 * Retain local intent until inclusion is verified or failure is explicit.
 * A CONFIRMED string alone is insufficient; cache inclusion must carry a
 * block association populated after the epoch handoff.
 */
fun resolvePendingWithdraw(
    depositStillDeposited: Boolean,
    withdrawTxStatus: String?,
    chainVerdict: DaoUnlockChainVerdict = DaoUnlockChainVerdict.UNKNOWN,
    cacheInclusionVerified: Boolean = false,
): PendingWithdrawResolution = when {
    chainVerdict == DaoUnlockChainVerdict.REJECTED || withdrawTxStatus == "FAILED" -> PendingWithdrawResolution.CLEAR_FAILED
    !depositStillDeposited && (chainVerdict == DaoUnlockChainVerdict.COMMITTED ||
        (withdrawTxStatus == "CONFIRMED" && cacheInclusionVerified)) -> PendingWithdrawResolution.CLEAR_CONFIRMED
    withdrawTxStatus == "UNVERIFIED" -> PendingWithdrawResolution.OVERLAY
    else -> PendingWithdrawResolution.OVERLAY
}
