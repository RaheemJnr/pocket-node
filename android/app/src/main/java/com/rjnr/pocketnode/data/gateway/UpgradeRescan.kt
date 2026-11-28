package com.rjnr.pocketnode.data.gateway

import android.content.Context
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1.8.7.1 hotfix: the 1.8.6 -> 1.8.7 upgrade wiped the native scan data and,
 * through [VerificationCacheGate.reconcile], every wallet's `sync_progress`.
 * Registration then fell back to the mode-derived start (the tip for
 * NEW_WALLET), so funds older than that start never came back.
 *
 * Two parts:
 *  - Part B ([upgradeOwnStart], used by the gate): on a first-ever or changed
 *    verification generation, keep each wallet's sync position at its own
 *    start (its earliest known block minus a margin) instead of dropping it.
 *  - Part A ([UpgradeRescanAdvisor]): a wallet that already went through
 *    1.8.7 has lost those block hints. Home offers a one-time CUSTOM scan
 *    from an estimated block. Never automatic, never deletes anything.
 */

/** Blocks kept below the earliest known block when seeding a wallet's own start. */
internal const val OWN_START_MARGIN_BLOCKS = 1_000L

/** Blocks kept below the estimated start of a wallet's history in the banner offer. */
internal const val UPGRADE_RESCAN_MARGIN_BLOCKS = 10_000L

/**
 * Deliberately fast block time for the time-to-block estimate. CKB averages
 * roughly 8 to 13 s per block; assuming 6 s overestimates how many blocks
 * passed since a time, so the estimated block is lower than the real one
 * and the scan starts earlier, never later.
 */
internal const val CONSERVATIVE_BLOCK_MS = 6_000L

/** RECENT registers from tip - 200,000 (see `SyncMode.toFromBlock`). */
internal const val RECENT_WINDOW_BLOCKS = 200_000L

/** How close to the tip the saved progress must be to count as synced. */
internal const val SYNCED_TOLERANCE_BLOCKS = 100L

/**
 * Part B: the block a wallet's sync position is reset to on a verification
 * handoff. The minimum of:
 *  - the start it was registered from before the handoff,
 *  - its earliest cached transaction block minus [OWN_START_MARGIN_BLOCKS],
 *  - its earliest DAO deposit block minus [OWN_START_MARGIN_BLOCKS],
 *  - the mode start when it does not depend on the tip (FULL_HISTORY: 0,
 *    CUSTOM: the chosen height).
 * Floored at 0. Null when the wallet has no evidence at all (no previous
 * start, no history): registration then uses the mode start, exactly as a
 * fresh install does.
 */
internal fun upgradeOwnStart(
    previousStart: Long?,
    earliestTxBlock: Long?,
    earliestDaoBlock: Long?,
    syncMode: SyncMode,
    customHeight: Long?,
): Long? {
    val evidence = listOfNotNull(
        previousStart,
        earliestTxBlock?.let { it - OWN_START_MARGIN_BLOCKS },
        earliestDaoBlock?.let { it - OWN_START_MARGIN_BLOCKS },
    )
    if (evidence.isEmpty()) return null
    val modeStart = when (syncMode) {
        SyncMode.FULL_HISTORY -> 0L
        SyncMode.CUSTOM -> customHeight
        else -> null
    }
    return (evidence + listOfNotNull(modeStart)).min().coerceAtLeast(0L)
}

/**
 * The value written to `sync_progress` for an own start. Registration reads a
 * saved block of 0 as "no progress" and falls back to the mode start (the tip
 * for NEW_WALLET), so a genesis start outside FULL_HISTORY is stored as 1.
 */
internal fun ownStartSavedBlock(ownStart: Long, syncMode: SyncMode): Long =
    if (ownStart == 0L && syncMode != SyncMode.FULL_HISTORY) 1L else ownStart

/**
 * Block height at [timeMs], estimated back from the tip with
 * [CONSERVATIVE_BLOCK_MS]. Errs low. Floored at 0.
 */
internal fun conservativeBlockAt(tipNumber: Long, tipTimestampMs: Long, timeMs: Long): Long {
    if (timeMs >= tipTimestampMs) return tipNumber
    val elapsed = tipTimestampMs - timeMs
    val blocks = (elapsed + CONSERVATIVE_BLOCK_MS - 1) / CONSERVATIVE_BLOCK_MS
    return (tipNumber - blocks).coerceAtLeast(0L)
}

/**
 * Part A: the block the banner offers to scan from, or null when there is
 * nothing to estimate from.
 *  - [blockHints]: real block numbers that survived (each is a lower bound).
 *  - [earliestTimeMs]: the earliest wall-clock time this wallet's history can
 *    start at on this device (wallet creation, app install, a kept row
 *    timestamp). Mapped to a block with [conservativeBlockAt]. A RECENT
 *    wallet registered 200,000 blocks below the tip of that time, so that
 *    window is subtracted too.
 * The result is the minimum minus [UPGRADE_RESCAN_MARGIN_BLOCKS], floored at
 * 0, so it is never above the oldest hint.
 */
internal fun estimateUpgradeRescanStart(
    tipNumber: Long,
    tipTimestampMs: Long,
    earliestTimeMs: Long?,
    blockHints: List<Long>,
    syncMode: SyncMode,
): Long? {
    val candidates = blockHints.filter { it > 0L }.toMutableList()
    if (earliestTimeMs != null && earliestTimeMs > 0L && tipNumber > 0L && tipTimestampMs > 0L) {
        val window = if (syncMode == SyncMode.RECENT) RECENT_WINDOW_BLOCKS else 0L
        candidates += conservativeBlockAt(tipNumber, tipTimestampMs, earliestTimeMs) - window
    }
    if (candidates.isEmpty()) return null
    return (candidates.min() - UPGRADE_RESCAN_MARGIN_BLOCKS).coerceAtLeast(0L)
}

/**
 * Deliberately slow block time for an upper bound: CKB never averages 30 s
 * per block, so a block estimated with it is at or above the real one.
 */
internal const val SLOW_BLOCK_MS = 30_000L

/** Block height at [timeMs], estimated back from the tip with [SLOW_BLOCK_MS]. Errs high. */
internal fun optimisticBlockAt(tipNumber: Long, tipTimestampMs: Long, timeMs: Long): Long {
    if (timeMs >= tipTimestampMs) return tipNumber
    return (tipNumber - (tipTimestampMs - timeMs) / SLOW_BLOCK_MS).coerceAtLeast(0L)
}

/**
 * Whether a leftover UNVERIFIED row lies below the wallet's current start.
 *
 * Neither `sync_progress.lightStartBlockNumber` nor the light client's
 * registered scripts hold the start: every registration rewrites the first
 * to the resume block, and the second reports the scan cursor. The start in
 * force is the one the last applied sync setting produced, and a resync that
 * lands writes that setting:
 *  - FULL_HISTORY starts at 0, so nothing lies below it.
 *  - CUSTOM starts at its height. A row lies below only when its latest
 *    possible block (a kept block number, or [optimisticBlockAt] its
 *    `cachedAt`, the last time 1.8.6 wrote it, which is after the block) is
 *    below that height. Otherwise the start may have covered it.
 *  - NEW_WALLET and RECENT restarted at the tip of the 1.8.7 upgrade (RECENT:
 *    200,000 blocks below it). The rows were all cached before that, and a
 *    complete scan writes every row it reaches back as CONFIRMED, so a row
 *    still UNVERIFIED after it lies below the start.
 */
internal fun leftoverBelowStart(
    syncMode: SyncMode,
    customHeight: Long?,
    rowLatestTimesMs: List<Long>,
    rowBlockHints: List<Long>,
    tipNumber: Long,
    tipTimestampMs: Long,
): Boolean = when (syncMode) {
    SyncMode.FULL_HISTORY -> false
    SyncMode.CUSTOM -> customHeight != null && customHeight > 0L && (
        rowBlockHints.any { it < customHeight } ||
            (tipTimestampMs > 0L && rowLatestTimesMs.any { it > 0L && optimisticBlockAt(tipNumber, tipTimestampMs, it) < customHeight })
        )
    else -> true
}

/** Per wallet + network persistence for the 1.8.7.1 upgrade scan. */
class UpgradeRescanFlags(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDone(walletId: String, network: String): Boolean = prefs.getBoolean(doneKey(walletId, network), false)

    fun markDone(walletId: String, network: String) {
        prefs.edit().putBoolean(doneKey(walletId, network), true).apply()
    }

    fun offered(walletId: String, network: String): Long? =
        prefs.getLong(offeredKey(walletId, network), -1L).takeIf { it >= 0L }

    fun recordOffered(walletId: String, network: String, block: Long) {
        prefs.edit().putLong(offeredKey(walletId, network), block).apply()
    }

    private fun doneKey(walletId: String, network: String) = "done_${network}_$walletId"
    private fun offeredKey(walletId: String, network: String) = "offered_${network}_$walletId"

    companion object {
        const val PREFS = "upgrade_rescan_1871"
    }
}

/**
 * Part A: decides whether Home shows the "older transactions are not shown"
 * banner for a wallet, and records what the user did about it.
 *
 * A wallet is affected when all of these hold:
 *  - no done flag for this wallet and network, and not dismissed this launch;
 *  - its saved progress is within [SYNCED_TOLERANCE_BLOCKS] of the tip, so
 *    the registered range has been fully scanned;
 *  - it still has non-local UNVERIFIED transactions (the 1.8.7 reconcile left
 *    every cached row UNVERIFIED; the scan re-confirms the ones it reaches);
 *  - one of them lies below the wallet's current start ([leftoverBelowStart]).
 *    A start that already covered the history (full history from 0, a custom
 *    start below the rows) gets no banner, even with stale rows left over;
 *  - the estimated scan start is below the start it is registered from now.
 * Fresh installs have no UNVERIFIED rows; part B marks every wallet it seeds
 * from clean (pre-1.8.7) data as done.
 */
@Singleton
class UpgradeRescanAdvisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val syncPreferences: SyncPreferences,
) {
    private val flags = UpgradeRescanFlags(context)
    private val dismissed = mutableSetOf<Pair<String, String>>()

    internal var installTimeMs: () -> Long? = {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrNull()?.takeIf { it > 0L }
    }

    suspend fun offer(walletId: String, network: NetworkType, tipNumber: Long, tipTimestampMs: Long): Long? {
        if (walletId.isEmpty() || tipNumber <= 0L) return null
        val net = network.name
        if (flags.isDone(walletId, net) || synchronized(dismissed) { (walletId to net) in dismissed }) return null
        val mode = syncPreferences.getSyncMode(network, walletId)
        if (mode == SyncMode.FULL_HISTORY) return null
        val customHeight = syncPreferences.getCustomBlockHeight(network, walletId)
        val progress = database.syncProgressDao().get(walletId, net) ?: return null
        if (progress.localSavedBlockNumber < tipNumber - SYNCED_TOLERANCE_BLOCKS) return null

        val db = database.openHelper.readableDatabase
        val timestamps = mutableListOf<Long>()
        val latestTimes = mutableListOf<Long>()
        val hints = mutableListOf<Long>()
        var unverified = 0
        db.query(
            "SELECT timestamp, blockNumber, cachedAt FROM transactions " +
                "WHERE walletId = ? AND network = ? AND status = 'UNVERIFIED' AND isLocal = 0",
            arrayOf<Any?>(walletId, net),
        ).use { c ->
            while (c.moveToNext()) {
                unverified++
                val timestamp = c.getLong(0).takeIf { it > 0L }
                timestamp?.let(timestamps::add)
                parseBlock(c.getString(1))?.let(hints::add)
                listOfNotNull(timestamp, c.getLong(2).takeIf { it > 0L }).minOrNull()?.let(latestTimes::add)
            }
        }
        if (unverified == 0) return null
        if (!leftoverBelowStart(mode, customHeight, latestTimes, hints, tipNumber, tipTimestampMs)) return null
        db.query(
            "SELECT depositBlockNumber FROM dao_cells WHERE walletId = ? AND network = ? AND depositBlockNumber > 0",
            arrayOf<Any?>(walletId, net),
        ).use { c -> while (c.moveToNext()) hints += c.getLong(0) }

        val createdAt = database.walletDao().getById(walletId)?.createdAt?.takeIf { it > 0L }
        val earliestTime = listOfNotNull(createdAt, installTimeMs(), timestamps.minOrNull()).minOrNull()
        val start = estimateUpgradeRescanStart(tipNumber, tipTimestampMs, earliestTime, hints, mode) ?: return null
        if (start >= progress.lightStartBlockNumber) return null
        flags.recordOffered(walletId, net, start)
        return start
    }

    /** "Not now": hidden until the next launch. */
    fun dismissForLaunch(walletId: String, network: NetworkType) {
        synchronized(dismissed) { dismissed += walletId to network.name }
    }

    /**
     * Called after a resync landed. Full history, or a custom start at or
     * below the offered block, covers what the banner would scan.
     */
    fun recordResync(walletId: String, network: NetworkType, syncMode: SyncMode, customHeight: Long?) {
        if (walletId.isEmpty()) return
        val net = network.name
        val offered = flags.offered(walletId, net)
        val covers = syncMode == SyncMode.FULL_HISTORY ||
            (syncMode == SyncMode.CUSTOM && customHeight != null && offered != null && customHeight <= offered)
        if (covers) flags.markDone(walletId, net)
    }

    fun isDone(walletId: String, network: NetworkType): Boolean = flags.isDone(walletId, network.name)

    private fun parseBlock(hex: String?): Long? =
        hex?.removePrefix("0x")?.toLongOrNull(16)?.takeIf { it > 0L }
}
