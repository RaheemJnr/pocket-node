package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Network fee" row on the transaction detail sheet (#497).
 *
 * Two decisions are pinned here because both detail sheets (Home and
 * Activity) render from them, and neither is obvious from the field alone:
 *
 *  - [TransactionRecord.paysNetworkFee] decides whether the row appears at
 *    all. A plain receive was paid for by the sender, so no fee row.
 *  - [TransactionRecord.formattedFee] returns null for "not known yet",
 *    which the sheets render as "Pending". It is never conflated with a
 *    known 0, which would read as "this transaction was free".
 */
class TransactionRecordFeeTest {

    private fun makeRecord(
        direction: String = "out",
        feeShannons: Long? = null
    ) = TransactionRecord(
        txHash = "0x" + "ab".repeat(32),
        blockNumber = "0x100",
        blockHash = "0x" + "cd".repeat(32),
        timestamp = 0L,
        balanceChange = "0x5f5e100",
        direction = direction,
        fee = "0x0",
        confirmations = 10,
        feeShannons = feeShannons
    )

    // --- which rows carry a fee at all ---

    @Test
    fun `incoming hides the fee row`() {
        assertFalse(makeRecord(direction = "in").paysNetworkFee())
    }

    @Test
    fun `every direction we originate shows the fee row`() {
        listOf("out", "self", "dao_deposit", "dao_withdraw", "dao_unlock").forEach {
            assertTrue("$it should pay a network fee", makeRecord(direction = it).paysNetworkFee())
        }
    }

    // --- formatting ---

    @Test
    fun `formats the smoke transaction fee`() {
        // #497 acceptance: 1,000 shannons renders as 0.00001 CKB.
        assertEquals("0.00001 CKB", makeRecord(feeShannons = 1_000L).formattedFee())
    }

    @Test
    fun `trims trailing zeros but keeps full precision`() {
        assertEquals("1 CKB", makeRecord(feeShannons = 100_000_000L).formattedFee())
        assertEquals("0.00000001 CKB", makeRecord(feeShannons = 1L).formattedFee())
        assertEquals("0.001 CKB", makeRecord(feeShannons = 100_000L).formattedFee())
    }

    @Test
    fun `unknown fee is null so the sheet can say Pending`() {
        assertNull(makeRecord(feeShannons = null).formattedFee())
    }

    @Test
    fun `a known zero fee is not the same as unknown`() {
        assertEquals("0 CKB", makeRecord(feeShannons = 0L).formattedFee())
    }

    @Test
    fun `records that never set a fee default to unknown`() {
        // Pre-#497 cached rows deserialize without the field; they must land
        // on "Pending", not on a fabricated 0.
        assertNull(makeRecord().feeShannons)
    }
}
