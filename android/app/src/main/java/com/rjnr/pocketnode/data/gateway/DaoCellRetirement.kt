package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus

/** What to do with a cached dao_cells row the live scan no longer returns. */
enum class CachedDaoCellFate {
    /** Not a windowing/spend decision at all, leave the row exactly as it is. */
    IGNORE,

    /** The cell was spent: mark the row COMPLETED so it never renders again. */
    RETIRE,

    /**
     * An unlock for this cell is broadcast but not yet terminal. Show the
     * cached row so the position stays visible and confirming, and retire
     * nothing until the transaction resolves (#529).
     */
    UNLOCK_IN_FLIGHT,

    /**
     * The cell predates the script's sync window, so the light client cannot
     * see it: append it to the list flagged `outsideSyncWindow` (#332).
     */
    OUTSIDE_WINDOW,
}

/** How far along a persisted phase-2 unlock marker is (#529). */
enum class DaoUnlockMarkerState {
    /** Broadcast, not yet resolved either way. The marker must be kept. */
    IN_FLIGHT,

    /** The unlock is on chain: its cell is spent for good. */
    CONFIRMED,

    /** The unlock will never land: the cell is still the user's to claim. */
    FAILED,
}

/** What the chain itself says about an unlock transaction (#529). */
enum class DaoUnlockChainVerdict {
    /** On chain. The withdrawing cell is spent for good. */
    COMMITTED,

    /** The node rejected it. It will never land. */
    REJECTED,

    /** Still in the pool, so it may yet land. */
    IN_POOL,

    /**
     * No answer: unknown to the node, the lookup failed, or the node is not
     * synced enough to say. Never treated as a spend.
     */
    UNKNOWN,
}

/**
 * How long an unresolved marker is trusted before the chain is consulted
 * about it (#529). Every local path that scores a transaction
 * (BroadcastWatchdog, the activity walk, StartupReconciler) resolves within
 * minutes of the app running, so a marker still unscored after this has been
 * missed rather than delayed.
 */
const val DAO_UNLOCK_MARKER_GRACE_MS: Long = 2 * 60 * 60 * 1000L

/**
 * The outer bound on believing a transaction that the chain still reports as
 * merely in the pool. CKB proposes and commits in minutes; a day later an
 * in-pool answer is a stale local mempool entry, not a pending spend.
 */
const val DAO_UNLOCK_MARKER_TTL_MS: Long = 24 * 60 * 60 * 1000L

/**
 * How far along one unlock marker is.
 *
 * The local transaction cache is the authority while it has an answer. Past
 * [DAO_UNLOCK_MARKER_GRACE_MS] with no answer, the chain is asked, and only
 * the chain can promote a marker to CONFIRMED.
 *
 * Everything unresolved resolves to FAILED, never CONFIRMED. The two mistakes
 * are not symmetrical: a restored position that was in fact spent costs the
 * user one wasted tap, which the unlock's own fail-fast catches, while a
 * wrongly retired position disappears from the DAO screen and from the
 * balance, and the preflight then insists it was already unlocked. Guessing
 * in favour of the user's funds is the only safe default.
 *
 * Note the asymmetry with phase 1 ([resolvePendingWithdraw]), where the
 * deposit cell disappearing is enough on its own. Phase 2 cannot use that
 * shortcut: an unlock's output is an ordinary cell, so for a deposit outside
 * the sync window "absent from the scan" is indistinguishable from "never
 * visible", which is exactly the case that would hide funds.
 */
fun daoUnlockMarkerState(
    unlockTxStatus: String?,
    markerAgeMs: Long,
    chainVerdict: DaoUnlockChainVerdict,
): DaoUnlockMarkerState = when {
    unlockTxStatus == "CONFIRMED" -> DaoUnlockMarkerState.CONFIRMED
    unlockTxStatus == "FAILED" -> DaoUnlockMarkerState.FAILED
    markerAgeMs < DAO_UNLOCK_MARKER_GRACE_MS -> DaoUnlockMarkerState.IN_FLIGHT
    chainVerdict == DaoUnlockChainVerdict.COMMITTED -> DaoUnlockMarkerState.CONFIRMED
    chainVerdict == DaoUnlockChainVerdict.REJECTED -> DaoUnlockMarkerState.FAILED
    chainVerdict == DaoUnlockChainVerdict.IN_POOL &&
        markerAgeMs < DAO_UNLOCK_MARKER_TTL_MS -> DaoUnlockMarkerState.IN_FLIGHT
    else -> DaoUnlockMarkerState.FAILED
}

/** What the DAO refresh does with one unlock marker (#529). */
enum class PendingUnlockResolution {
    /** Keep the marker; paint the cell UNLOCKING ("Confirming…"). */
    OVERLAY,

    /** Unlock landed: retire the cached row COMPLETED and drop the marker. */
    RETIRE,

    /** Unlock will not land: hand the cell back as UNLOCKABLE and drop the marker. */
    RESTORE,
}

/**
 * Decide a marker's fate from its state and whether its cell is still in the
 * live scan.
 *
 * Nothing is retired while the cell is still listed: the write-through
 * rewrites that row from the live scan on every refresh, so a retirement
 * there would be undone a moment later. Retirement waits for the cell to be
 * gone AND the transaction to have landed.
 */
fun resolvePendingUnlock(
    withdrawingCellStillLive: Boolean,
    state: DaoUnlockMarkerState,
): PendingUnlockResolution = when {
    state == DaoUnlockMarkerState.FAILED -> PendingUnlockResolution.RESTORE
    withdrawingCellStillLive -> PendingUnlockResolution.OVERLAY
    state == DaoUnlockMarkerState.CONFIRMED -> PendingUnlockResolution.RETIRE
    else -> PendingUnlockResolution.OVERLAY
}

/**
 * Decide the fate of one cached DAO row that is absent from the live scan.
 *
 * Absence has three very different causes and they must not be confused:
 * the cell was spent (retire it), an unlock for it is still in flight (hold
 * it, confirming), or the cell is simply older than the window the light
 * client indexes (keep showing it, offer a deep rescan). The caller only
 * invokes this for rows already known to be absent.
 *
 * @param cachedStatus the row's persisted [DaoCellStatus] name.
 * @param depositBlockNumber the ORIGINAL deposit's block (a withdrawing
 *   cell's row carries the deposit block, not the withdraw block).
 * @param windowStart the script's current sync head, not its registration
 *   start, which is why a just-spent recent cell also looks "before the
 *   window" and needs the spend signals below to be decided first (#434).
 * @param consumedByLiveWithdraw the row's outpoint is an input of a live
 *   withdrawing cell's phase-1 transaction: phase 1 spent it (#434).
 * @param unlockState the phase-2 marker for this cell, or null if there is
 *   none. A FAILED marker deliberately falls through to the rules below: the
 *   cell was not spent, so it is classified exactly as if no unlock had ever
 *   been attempted (#529).
 */
fun resolveCachedDaoCell(
    cachedStatus: String,
    depositBlockNumber: Long,
    windowStart: Long,
    consumedByLiveWithdraw: Boolean,
    unlockState: DaoUnlockMarkerState?,
): CachedDaoCellFate = when {
    // DEPOSITING rows are optimistic pre-confirmation inserts with
    // blockNumber 0, not windowing victims; leave them alone.
    cachedStatus == DaoCellStatus.DEPOSITING.name -> CachedDaoCellFate.IGNORE
    consumedByLiveWithdraw -> CachedDaoCellFate.RETIRE
    unlockState == DaoUnlockMarkerState.CONFIRMED -> CachedDaoCellFate.RETIRE
    unlockState == DaoUnlockMarkerState.IN_FLIGHT -> CachedDaoCellFate.UNLOCK_IN_FLIGHT
    windowStart > 0 && depositBlockNumber in 1 until windowStart -> CachedDaoCellFate.OUTSIDE_WINDOW
    // Inside the window yet absent from the live scan: the cell was spent,
    // retire the cached row so it doesn't resurrect.
    else -> CachedDaoCellFate.RETIRE
}
