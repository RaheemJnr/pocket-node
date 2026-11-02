package com.rjnr.pocketnode.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingWithdrawResolutionTest {
    @Test fun `empty post-epoch scan does not confirm pending unknown or quarantined intent`() {
        for (status in listOf(null, "PENDING", "UNVERIFIED", "CONFIRMED")) {
            assertEquals(PendingWithdrawResolution.OVERLAY,
                resolvePendingWithdraw(false, status))
        }
    }
    @Test fun `later verified inclusion clears an absent deposit marker`() {
        assertEquals(PendingWithdrawResolution.CLEAR_CONFIRMED,
            resolvePendingWithdraw(false, "UNVERIFIED", DaoUnlockChainVerdict.COMMITTED))
        assertEquals(PendingWithdrawResolution.CLEAR_CONFIRMED,
            resolvePendingWithdraw(false, "CONFIRMED", cacheInclusionVerified = true))
    }
    @Test fun `confirmed inclusion waits for live deposit scan to catch up`() {
        assertEquals(PendingWithdrawResolution.OVERLAY,
            resolvePendingWithdraw(true, "CONFIRMED", DaoUnlockChainVerdict.COMMITTED))
    }
    @Test fun `explicit rejection or local failure clears marker for retry`() {
        assertEquals(PendingWithdrawResolution.CLEAR_FAILED,
            resolvePendingWithdraw(false, "UNVERIFIED", DaoUnlockChainVerdict.REJECTED))
        assertEquals(PendingWithdrawResolution.CLEAR_FAILED,
            resolvePendingWithdraw(true, "FAILED"))
    }
    @Test fun `present deposit with unresolved intent keeps overlay`() {
        assertEquals(PendingWithdrawResolution.OVERLAY, resolvePendingWithdraw(true, null))
        assertEquals(PendingWithdrawResolution.OVERLAY, resolvePendingWithdraw(true, "PENDING"))
    }
}
