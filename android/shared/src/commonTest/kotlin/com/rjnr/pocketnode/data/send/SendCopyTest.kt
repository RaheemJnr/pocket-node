package com.rjnr.pocketnode.data.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exact strings and the exact threshold, because both are user-facing
 * decisions rather than implementation detail.
 *
 * The error branches are ported from the app module's `SendErrorMessageTest`
 * and the sweep cases from `SendSweepWarningTest`, narrowed to the parts that
 * are now shared: the Android ViewModel tests still cover their own state
 * transitions, which stay on Android.
 */
class SendCopyTest {

    private val ckb = 100_000_000L

    // ---- error mapping ----

    @Test
    fun cellFetchFailuresPointAtSyncRatherThanAtTheNetwork() {
        assertEquals(
            "Could not fetch your available funds. Please ensure your wallet is synced and try again.",
            mapSendErrorMessage("Failed to get cells: timeout"),
        )
        assertEquals(
            "Not enough funds available. Please wait for your wallet to fully sync.",
            mapSendErrorMessage("No cells available for this address"),
        )
        assertEquals(
            "Not enough funds available. Please wait for your wallet to fully sync.",
            mapSendErrorMessage("Insufficient cells to cover 100 CKB"),
        )
    }

    @Test
    fun amountAndBuildFailuresNameTheCellModel() {
        assertEquals(
            "Insufficient balance for this transaction.",
            mapSendErrorMessage("Insufficient balance: need 200, have 100"),
        )
        assertEquals(
            "Minimum transfer amount is 61 CKB due to CKB's cell model.",
            mapSendErrorMessage("Output capacity below minimum 61 CKB"),
        )
        assertTrue(
            mapSendErrorMessage("Dust change refused: 3 CKB")
                .startsWith("This exact amount would leave less than 61 CKB of change"),
        )
    }

    /**
     * The four branches #27 exists for. Before the bridge returned a reason
     * these all collapsed into one "could not send" line, which sent users
     * looking for a network problem that was not there.
     */
    @Test
    fun broadcastRejectionsNameTheirRealCause() {
        assertEquals(
            "This send depends on a previous transaction that hasn't confirmed yet. " +
                "Wait for it to confirm, or reopen the app and try again.",
            mapSendErrorMessage("Broadcast rejected: Resolve failed Unknown(OutPoint(0xabc))"),
        )
        assertEquals(
            "Some of the coins for this transaction were already spent. " +
                "Reopen the app to refresh your balance, then try again.",
            mapSendErrorMessage("Broadcast rejected: Resolve failed Dead(OutPoint(0xabc))"),
        )
        assertEquals(
            "The wallet is still starting up. Please wait a moment and try again.",
            mapSendErrorMessage("light client not ready"),
        )
        assertEquals(
            "The network rejected this transaction. Reopen the app to refresh your wallet, then try again.",
            mapSendErrorMessage("Broadcast rejected: script verification failed"),
        )
    }

    @Test
    fun anUnrecognisedRejectionStillSaysTheNetworkRefusedIt() {
        assertEquals(
            "The network rejected this transaction. Please reopen the app and try again.",
            mapSendErrorMessage("Broadcast rejected: PoolRejectedRBF"),
        )
    }

    @Test
    fun aBareNullFromTheBridgeIsStillReportedAsASendFailure() {
        assertEquals(
            "Could not send the transaction. Please reopen the app and try again.",
            mapSendErrorMessage("Send failed - native returned null"),
        )
    }

    @Test
    fun anythingUnrecognisedCarriesTheRawReasonThrough() {
        assertEquals("Transaction failed: kaboom", mapSendErrorMessage("kaboom"))
    }

    /**
     * Order is load-bearing: "Insufficient balance" also contains neither
     * "sync" nor "json", but a message that matches two branches has to take
     * the earlier one. This pins the one pair that genuinely overlaps.
     */
    @Test
    fun theMostSpecificBranchWinsWhenTwoMatch() {
        // Contains both "broadcast" and "sync"; the broadcast branch is first.
        assertEquals(
            "Could not send the transaction. Please reopen the app and try again.",
            mapSendErrorMessage("broadcast failed while wallet was out of sync"),
        )
    }

    // ---- sweep warning ----

    @Test
    fun theThresholdIsOneMinimalCellOrOnePercentWhicheverIsLarger() {
        // 100 CKB balance: 1% is 1 CKB, so the floor wins.
        assertEquals(61 * ckb, sweepThreshold(100 * ckb))
        // 10,000 CKB balance: 1% is 100 CKB, which is above the floor.
        assertEquals(100 * ckb, sweepThreshold(10_000 * ckb))
    }

    @Test
    fun aSendLeavingPlentyBehindWarnsAboutNothing() {
        assertNull(
            sweepWarning(
                balanceShannons = 10_000 * ckb,
                amountShannons = 100 * ckb,
                feeShannons = 1_000L,
            ),
        )
    }

    @Test
    fun aSendLeavingLessThanACellReportsTheExactLeftover() {
        val warning = sweepWarning(
            balanceShannons = 200 * ckb,
            amountShannons = 150 * ckb,
            feeShannons = 1_000L,
        )
        assertEquals(50 * ckb - 1_000L, warning?.remainingShannons)
        assertEquals(true, warning?.belowMinCell)
    }

    /**
     * The 1% arm: 61 CKB left out of 50,000 is still effectively everything
     * gone, but the wallet can genuinely fund another transaction, so the
     * softer copy applies.
     */
    @Test
    fun aBigWalletSweptToOneCellWarnsWithoutClaimingItIsUnusable() {
        val warning = sweepWarning(
            balanceShannons = 50_000 * ckb,
            amountShannons = 49_939 * ckb,
            feeShannons = 0L,
        )
        assertEquals(61 * ckb, warning?.remainingShannons)
        assertEquals(false, warning?.belowMinCell)
    }

    @Test
    fun theFeeCountsTowardsWhatIsLeft() {
        // Amount alone leaves exactly the threshold, so only the fee tips it.
        val withoutFee = sweepWarning(
            balanceShannons = 200 * ckb,
            amountShannons = 139 * ckb,
            feeShannons = 0L,
        )
        assertNull(withoutFee)

        val withFee = sweepWarning(
            balanceShannons = 200 * ckb,
            amountShannons = 139 * ckb,
            feeShannons = 1L,
        )
        assertEquals(61 * ckb - 1L, withFee?.remainingShannons)
    }

    @Test
    fun aSendPastTheBalanceReportsZeroRatherThanANegativeLeftover() {
        val warning = sweepWarning(
            balanceShannons = 100 * ckb,
            amountShannons = 500 * ckb,
            feeShannons = 1_000L,
        )
        assertEquals(0L, warning?.remainingShannons)
        assertEquals(true, warning?.belowMinCell)
    }

    /**
     * A zero balance is "not loaded yet" far more often than it is "empty".
     * Warning on it would fire on every send made before the first tick.
     */
    @Test
    fun aBalanceOfZeroWarnsAboutNothing() {
        assertNull(sweepWarning(balanceShannons = 0L, amountShannons = 61 * ckb, feeShannons = 0L))
    }
}
