package com.rjnr.pocketnode.ui.screens.send

import com.rjnr.pocketnode.core.format.formatCkbTrimmed
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The send review sheet (#495) and the transaction detail sheet (#497) show
 * the same fee twice, and cross-checking one against the other is the whole
 * reason #497 exists. Two formatters meant "0.00001 CKB" on one screen and
 * "0.00001000 CKB" or "1.00" vs "1" on the other, which reads as a
 * discrepancy to a user who cannot see the code.
 *
 * Fees are unified on the shared trimmed formatter. Amounts and totals keep
 * [formatReviewCkb]'s 2-decimal minimum, which is pinned below so the
 * unification does not quietly widen.
 */
class ReviewFeeFormatTest {

    private fun detailSheetFee(shannons: Long): String? = TransactionRecord(
        txHash = "0x" + "ab".repeat(32),
        blockNumber = "0x100",
        blockHash = "0x" + "cd".repeat(32),
        timestamp = 0L,
        balanceChange = "0x5f5e100",
        direction = "out",
        fee = "0x0",
        confirmations = 10,
        feeShannons = shannons
    ).formattedFee()

    @Test
    fun `review sheet and detail sheet render the same fee string`() {
        listOf(
            1L,                 // one shannon
            1_000L,             // the #497 smoke fee
            10_000L,
            100_000L,           // the legacy flat 0.001 CKB fee
            12_345_678L,
            100_000_000L,       // a whole 1 CKB
        ).forEach { shannons ->
            assertEquals(
                "fee strings diverged at $shannons shannons",
                "${formatCkbTrimmed(shannons)} CKB",
                detailSheetFee(shannons)
            )
        }
    }

    @Test
    fun `the unified fee formatter trims rather than padding to two decimals`() {
        assertEquals("1", formatCkbTrimmed(100_000_000L))
        assertEquals("0.00001", formatCkbTrimmed(1_000L))
    }

    @Test
    fun `amount and total formatting is unchanged`() {
        // formatReviewCkb still pads to a 2-decimal minimum; only the fee row
        // moved off it.
        assertEquals("1.00", formatReviewCkb(100_000_000L))
        assertEquals("1,000.00", formatReviewCkb(100_000_000_000L))
        assertEquals("0.00001", formatReviewCkb(1_000L))
    }
}
