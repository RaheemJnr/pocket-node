package com.rjnr.pocketnode.data.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #332: the 0-live-cells rescue rescan looped indefinitely for DAO-heavy
 * wallets because the trigger counted typed (DAO) cells as "no cells", and
 * nothing stopped it from firing again after every refresh. The policy is
 * now pure and explicit:
 *  - a wallet whose balance sits in typed cells is NOT empty;
 *  - one attempt per wallet per process (the rescan itself takes hours on a
 *    long-history wallet — re-firing mid-recovery restarts it);
 *  - never while sync is still catching up (the missing cells may simply
 *    not have been scanned yet).
 */
class RescanPolicyTest {

    @Test
    fun `fires for genuinely empty wallet with history`() {
        assertTrue(
            shouldAttemptZeroCellRescan(
                spendableCapacity = 0L, typedCellCount = 0, hasTransactions = true,
                alreadyAttempted = false, isSyncing = false,
            )
        )
    }

    @Test
    fun `DAO-only wallet is not empty - no rescan`() {
        assertFalse(
            shouldAttemptZeroCellRescan(
                spendableCapacity = 0L, typedCellCount = 3, hasTransactions = true,
                alreadyAttempted = false, isSyncing = false,
            )
        )
    }

    @Test
    fun `does not re-fire after an attempt this session`() {
        assertFalse(
            shouldAttemptZeroCellRescan(
                spendableCapacity = 0L, typedCellCount = 0, hasTransactions = true,
                alreadyAttempted = true, isSyncing = false,
            )
        )
    }

    @Test
    fun `never fires mid-sync`() {
        assertFalse(
            shouldAttemptZeroCellRescan(
                spendableCapacity = 0L, typedCellCount = 0, hasTransactions = true,
                alreadyAttempted = false, isSyncing = true,
            )
        )
    }

    @Test
    fun `no transactions means nothing to rescue`() {
        assertFalse(
            shouldAttemptZeroCellRescan(
                spendableCapacity = 0L, typedCellCount = 0, hasTransactions = false,
                alreadyAttempted = false, isSyncing = false,
            )
        )
    }

    @Test
    fun `funded wallet never rescans`() {
        assertFalse(
            shouldAttemptZeroCellRescan(
                spendableCapacity = 61_00000000L, typedCellCount = 0, hasTransactions = true,
                alreadyAttempted = false, isSyncing = false,
            )
        )
    }
}
