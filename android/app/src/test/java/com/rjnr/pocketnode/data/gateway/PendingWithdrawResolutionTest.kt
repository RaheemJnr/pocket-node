package com.rjnr.pocketnode.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #347: decides what to do with a persisted in-flight DAO withdraw marker on
 * each DAO refresh, from two observable facts — whether the deposit cell is
 * still DEPOSITED in the live light-client scan, and the withdraw tx's status
 * in the local transactions cache (maintained by BroadcastWatchdog).
 */
class PendingWithdrawResolutionTest {

    @Test
    fun `deposit gone from scan means withdraw committed - clear`() {
        // Deposit cell consumed by the committed withdraw → a new LOCKED
        // withdrawing cell appears separately; retire the marker.
        assertEquals(
            PendingWithdrawResolution.CLEAR_CONFIRMED,
            resolvePendingWithdraw(depositStillDeposited = false, withdrawTxStatus = "PENDING"),
        )
    }

    @Test
    fun `deposit gone wins even if tx still shows pending`() {
        assertEquals(
            PendingWithdrawResolution.CLEAR_CONFIRMED,
            resolvePendingWithdraw(depositStillDeposited = false, withdrawTxStatus = null),
        )
    }

    @Test
    fun `deposit present and tx failed - clear so user can retry`() {
        assertEquals(
            PendingWithdrawResolution.CLEAR_FAILED,
            resolvePendingWithdraw(depositStillDeposited = true, withdrawTxStatus = "FAILED"),
        )
    }

    @Test
    fun `deposit present and tx pending - keep overlay`() {
        assertEquals(
            PendingWithdrawResolution.OVERLAY,
            resolvePendingWithdraw(depositStillDeposited = true, withdrawTxStatus = "PENDING"),
        )
    }

    @Test
    fun `deposit present and tx confirmed but not yet indexed - keep overlay`() {
        // Tx committed on-chain but the light client hasn't scanned the spend
        // yet; keep showing "Confirming" until the cell actually disappears.
        assertEquals(
            PendingWithdrawResolution.OVERLAY,
            resolvePendingWithdraw(depositStillDeposited = true, withdrawTxStatus = "CONFIRMED"),
        )
    }

    @Test
    fun `deposit present and tx status unknown - keep overlay`() {
        assertEquals(
            PendingWithdrawResolution.OVERLAY,
            resolvePendingWithdraw(depositStillDeposited = true, withdrawTxStatus = null),
        )
    }
}
