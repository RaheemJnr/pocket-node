package com.rjnr.pocketnode.ui.screens.home

/** Default staleness window for the background-sync pill (#286): 1 hour. */
const val BG_SYNC_STALE_THRESHOLD_MS = 3_600_000L

/**
 * True when the wallet hasn't observed sync progress for longer than
 * [thresholdMs]. Deliberately ignores whether background sync is enabled:
 * the 2026-06 research showed "enabled but silently dead" (OEM kill, 6h
 * dataSync budget, notification-permission revocation) is the most common
 * failure, so the pill must fire there too — the caller varies only the
 * copy. `null` timestamp = wallet has never synced; don't nag during
 * first-run.
 */
fun isBgSyncStale(
    lastSyncedAtMs: Long?,
    nowMs: Long,
    thresholdMs: Long = BG_SYNC_STALE_THRESHOLD_MS,
): Boolean = lastSyncedAtMs != null && (nowMs - lastSyncedAtMs) > thresholdMs
