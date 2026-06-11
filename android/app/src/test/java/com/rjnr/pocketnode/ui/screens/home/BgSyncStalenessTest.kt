package com.rjnr.pocketnode.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #286 staleness predicate. Key design point from the 2026-06 research:
 * the pill must fire on BOTH "background sync off and stale" AND
 * "enabled but the service silently died" (OEM kill, 6h budget, permission
 * revocation) — so the predicate is pure staleness; the caller picks copy
 * by the enabled flag.
 */
class BgSyncStalenessTest {

    private val hour = 3_600_000L

    @Test
    fun `never-synced wallet is not stale`() {
        // Fresh wallet pre-first-sync: no timestamp, no nagging.
        assertFalse(isBgSyncStale(lastSyncedAtMs = null, nowMs = 10 * hour))
    }

    @Test
    fun `recent sync is not stale`() {
        assertFalse(isBgSyncStale(lastSyncedAtMs = 10 * hour - 5 * 60_000L, nowMs = 10 * hour))
    }

    @Test
    fun `sync older than threshold is stale`() {
        assertTrue(isBgSyncStale(lastSyncedAtMs = 8 * hour, nowMs = 10 * hour))
    }

    @Test
    fun `exactly at threshold is not yet stale`() {
        assertFalse(isBgSyncStale(lastSyncedAtMs = 9 * hour, nowMs = 10 * hour))
    }

    @Test
    fun `custom threshold respected`() {
        assertTrue(
            isBgSyncStale(lastSyncedAtMs = 0L, nowMs = 16 * 60_000L, thresholdMs = 15 * 60_000L)
        )
    }
}
