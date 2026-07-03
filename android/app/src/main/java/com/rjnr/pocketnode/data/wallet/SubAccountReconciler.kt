package com.rjnr.pocketnode.data.wallet

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probe for "does this lock script have any on-chain history?". Same
 * fun-interface seam idiom as BroadcastWatchdog's TransactionStatusGateway:
 * production wires nativeGetTransactions(limit=1); tests fake it. Returns
 * null when the light client can't answer right now (indeterminate — the
 * reconciler retries on a later pass rather than mis-classifying).
 */
fun interface SubAccountActivityProbe {
    suspend fun hasActivity(scriptArgs: String): Boolean?
}

/**
 * Resolves PENDING sub-account candidates (#82 phase 2).
 *
 * A candidate's activity can only be judged once the light client has
 * scanned its script over the sync window. Each pass:
 *
 *  - PENDING + probe sees history            -> FOUND (offer restore)
 *  - PENDING + no history + scanned near tip -> EMPTY (retire)
 *  - script not registered / probe null      -> stays PENDING, retry later
 *
 * EMPTY requires the scanned block within [NEAR_TIP_MARGIN] of the tip:
 * declaring "no history" mid-scan would permanently hide a real
 * sub-account whose transactions live in the unscanned remainder.
 */
@Singleton
class SubAccountReconciler @Inject constructor(
    private val candidateDao: SubAccountCandidateDao,
    private val probe: SubAccountActivityProbe,
) {

    private var lastRunAt = 0L

    /**
     * Throttled entry point for the sync poll. [scannedByArgs] is the
     * per-script scanned height from nativeGetScripts; [tipHeight] the
     * current chain tip (0 = unknown, EMPTY never declared).
     */
    suspend fun reconcile(scannedByArgs: Map<String, Long>, tipHeight: Long) {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < RUN_INTERVAL_MS) return
        lastRunAt = now
        reconcileNow(scannedByArgs, tipHeight)
    }

    /** Un-throttled core — direct target for unit tests. */
    suspend fun reconcileNow(scannedByArgs: Map<String, Long>, tipHeight: Long) {
        val pending = candidateDao.getByState(SubAccountCandidateEntity.STATE_PENDING)
        if (pending.isEmpty()) return

        pending.forEach { candidate ->
            val scanned = scannedByArgs[candidate.scriptArgs] ?: return@forEach
            val active = probe.hasActivity(candidate.scriptArgs) ?: return@forEach
            when {
                active -> {
                    Log.i(
                        TAG,
                        "Candidate found: parent=${candidate.parentWalletId} " +
                            "index=${candidate.accountIndex} has on-chain history"
                    )
                    candidateDao.updateState(
                        candidate.parentWalletId,
                        candidate.accountIndex,
                        SubAccountCandidateEntity.STATE_FOUND,
                    )
                }
                tipHeight > 0 && scanned >= tipHeight - NEAR_TIP_MARGIN -> {
                    candidateDao.updateState(
                        candidate.parentWalletId,
                        candidate.accountIndex,
                        SubAccountCandidateEntity.STATE_EMPTY,
                    )
                }
                // else: still scanning — leave PENDING.
            }
        }
    }

    companion object {
        private const val TAG = "SubAccountReconciler"

        /** Scanned-to-within-this-many-blocks of tip counts as "scan complete". */
        const val NEAR_TIP_MARGIN = 1_000L

        /** One pass per interval — the sync poll fires every few seconds. */
        const val RUN_INTERVAL_MS = 30_000L
    }
}
