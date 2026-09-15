package com.rjnr.pocketnode.ui.transaction

import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.sync.BroadcastWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-to-label mapping and elapsed formatting for the pending-transaction UI
 * (#432). Pure functions, so these are plain JUnit with no Robolectric: the
 * assertions are on resource IDs and numeric args, not rendered strings.
 */
class TransactionStatusUiTest {

    private fun broadcast(
        state: String = "BROADCAST",
        nullCount: Int = 0,
        createdAt: Long = 1_000L,
    ) = BroadcastInfo(state = state, nullCount = nullCount, createdAt = createdAt)

    // ─── displayState ─────────────────────────────────────────────────────────

    @Test
    fun `ledger FAILED is failed regardless of broadcast row`() {
        assertEquals(
            TxDisplayState.FAILED,
            TransactionStatusUi.displayState("FAILED", confirmations = 0, broadcast = null),
        )
        assertEquals(
            TxDisplayState.FAILED,
            TransactionStatusUi.displayState(
                "FAILED", confirmations = 0, broadcast = broadcast(state = "BROADCASTING"),
            ),
        )
    }

    @Test
    fun `broadcast row FAILED is failed even when the ledger row is still pending`() {
        // The watchdog writes the cache row before the CAS; a crash between the
        // two must not show the tx as pending forever.
        assertEquals(
            TxDisplayState.FAILED,
            TransactionStatusUi.displayState(
                "PENDING", confirmations = 0, broadcast = broadcast(state = "FAILED"),
            ),
        )
    }

    @Test
    fun `confirmations make a row confirmed`() {
        assertEquals(
            TxDisplayState.CONFIRMED,
            TransactionStatusUi.displayState("PENDING", confirmations = 1, broadcast = null),
        )
    }

    @Test
    fun `CONFIRMED status with zero confirmations still reads as confirmed`() {
        // Synced rows can arrive before the confirmation count is filled in.
        assertEquals(
            TxDisplayState.CONFIRMED,
            TransactionStatusUi.displayState("CONFIRMED", confirmations = 0, broadcast = null),
        )
    }

    @Test
    fun `BROADCASTING row is distinguished from in-pool pending`() {
        assertEquals(
            TxDisplayState.BROADCASTING,
            TransactionStatusUi.displayState(
                "PENDING", confirmations = 0, broadcast = broadcast(state = "BROADCASTING"),
            ),
        )
        assertEquals(
            TxDisplayState.PENDING,
            TransactionStatusUi.displayState(
                "PENDING", confirmations = 0, broadcast = broadcast(state = "BROADCAST"),
            ),
        )
    }

    @Test
    fun `pending row with no broadcast record falls back to pending`() {
        // Incoming transactions seen on chain but not yet confirmed have no
        // pending_broadcasts row at all.
        assertEquals(
            TxDisplayState.PENDING,
            TransactionStatusUi.displayState("PENDING", confirmations = 0, broadcast = null),
        )
    }

    @Test
    fun `null status defaults to pending rather than crashing`() {
        assertEquals(
            TxDisplayState.PENDING,
            TransactionStatusUi.displayState(null, confirmations = 0, broadcast = null),
        )
    }

    // ─── labels ───────────────────────────────────────────────────────────────

    @Test
    fun `every display state maps to its own label`() {
        assertEquals(R.string.tx_status_broadcasting, TransactionStatusUi.statusLabelRes(TxDisplayState.BROADCASTING))
        assertEquals(R.string.tx_status_pending, TransactionStatusUi.statusLabelRes(TxDisplayState.PENDING))
        assertEquals(R.string.tx_status_confirmed, TransactionStatusUi.statusLabelRes(TxDisplayState.CONFIRMED))
        assertEquals(R.string.tx_status_failed, TransactionStatusUi.statusLabelRes(TxDisplayState.FAILED))

        val ids = TxDisplayState.entries.map { TransactionStatusUi.statusLabelRes(it) }
        assertEquals("labels must be distinct", ids.size, ids.toSet().size)
    }

    @Test
    fun `elapsed time is shown only while in flight`() {
        assertTrue(TransactionStatusUi.showsElapsed(TxDisplayState.BROADCASTING))
        assertTrue(TransactionStatusUi.showsElapsed(TxDisplayState.PENDING))
        assertFalse(TransactionStatusUi.showsElapsed(TxDisplayState.CONFIRMED))
        assertFalse(TransactionStatusUi.showsElapsed(TxDisplayState.FAILED))
    }

    // ─── formatElapsed ────────────────────────────────────────────────────────

    @Test
    fun `under one minute has no number`() {
        val label = TransactionStatusUi.formatElapsed(59_999L)
        assertEquals(R.string.tx_elapsed_under_minute, label.res)
        assertNull(label.value)
    }

    @Test
    fun `exactly one minute crosses into the minutes bucket`() {
        val label = TransactionStatusUi.formatElapsed(60_000L)
        assertEquals(R.string.tx_elapsed_minutes, label.res)
        assertEquals(1, label.value)
    }

    @Test
    fun `minutes truncate rather than round`() {
        // 2 min 59 s is "2 min": rounding up would show "3 min" for a tx that
        // has not been waiting three minutes.
        val label = TransactionStatusUi.formatElapsed(179_000L)
        assertEquals(R.string.tx_elapsed_minutes, label.res)
        assertEquals(2, label.value)
    }

    @Test
    fun `fifty nine minutes stays in minutes and sixty flips to hours`() {
        assertEquals(59, TransactionStatusUi.formatElapsed(59L * 60_000L).value)
        assertEquals(R.string.tx_elapsed_minutes, TransactionStatusUi.formatElapsed(59L * 60_000L).res)

        val hour = TransactionStatusUi.formatElapsed(60L * 60_000L)
        assertEquals(R.string.tx_elapsed_hours, hour.res)
        assertEquals(1, hour.value)
    }

    @Test
    fun `twenty three hours stays in hours and twenty four flips to days`() {
        val nearlyADay = TransactionStatusUi.formatElapsed(23L * 3_600_000L)
        assertEquals(R.string.tx_elapsed_hours, nearlyADay.res)
        assertEquals(23, nearlyADay.value)

        val day = TransactionStatusUi.formatElapsed(24L * 3_600_000L)
        assertEquals(R.string.tx_elapsed_days, day.res)
        assertEquals(1, day.value)
    }

    @Test
    fun `negative elapsed clamps to the under-a-minute bucket`() {
        // Device clock moved backwards between the broadcast and the render.
        val label = TransactionStatusUi.formatElapsed(-5_000L)
        assertEquals(R.string.tx_elapsed_under_minute, label.res)
        assertNull(label.value)
    }

    @Test
    fun `zero elapsed is under a minute`() {
        assertEquals(R.string.tx_elapsed_under_minute, TransactionStatusUi.formatElapsed(0L).res)
    }

    // ─── pendingSince ─────────────────────────────────────────────────────────

    @Test
    fun `broadcast createdAt wins over the ledger timestamp`() {
        assertEquals(
            1_000L,
            TransactionStatusUi.pendingSince(rowTimestampMillis = 9_000L, broadcast = broadcast(createdAt = 1_000L)),
        )
    }

    @Test
    fun `falls back to the ledger timestamp without a broadcast row`() {
        assertEquals(9_000L, TransactionStatusUi.pendingSince(9_000L, broadcast = null))
    }

    @Test
    fun `unusable timestamps yield null so callers omit the elapsed suffix`() {
        assertNull(TransactionStatusUi.pendingSince(0L, broadcast = null))
        assertNull(TransactionStatusUi.pendingSince(0L, broadcast = broadcast(createdAt = 0L)))
        // A zero createdAt still falls through to a usable row timestamp.
        assertEquals(9_000L, TransactionStatusUi.pendingSince(9_000L, broadcast = broadcast(createdAt = 0L)))
    }

    // ─── failureReasonRes ─────────────────────────────────────────────────────

    @Test
    fun `no broadcast row gives the generic reason`() {
        assertEquals(R.string.tx_failed_reason_unknown, TransactionStatusUi.failureReasonRes(null))
    }

    @Test
    fun `repeated not-found reads as dropped`() {
        assertEquals(
            R.string.tx_failed_reason_dropped,
            TransactionStatusUi.failureReasonRes(broadcast(state = "FAILED", nullCount = 3)),
        )
        assertEquals(
            R.string.tx_failed_reason_dropped,
            TransactionStatusUi.failureReasonRes(broadcast(state = "FAILED", nullCount = 7)),
        )
    }

    @Test
    fun `a reset null count reads as rejected while in pool`() {
        // The watchdog zeroes nullCount on every healthy in-pool check, so a
        // FAILED row at zero got there by timing out in the pool.
        assertEquals(
            R.string.tx_failed_reason_rejected,
            TransactionStatusUi.failureReasonRes(broadcast(state = "FAILED", nullCount = 0)),
        )
        assertEquals(
            R.string.tx_failed_reason_rejected,
            TransactionStatusUi.failureReasonRes(broadcast(state = "FAILED", nullCount = 2)),
        )
    }

    @Test
    fun `reason threshold tracks the watchdog constant`() {
        assertEquals(BroadcastWatchdog.NULL_THRESHOLD, TransactionStatusUi.NULL_THRESHOLD)
    }
}
