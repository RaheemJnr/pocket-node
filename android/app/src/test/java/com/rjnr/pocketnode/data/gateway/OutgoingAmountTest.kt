package com.rjnr.pocketnode.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pending activity row used to store min(all output capacities) as the
 * outgoing amount. When change < amount sent, the smallest output IS the
 * change, so a 150,000 CKB send showed "-17,950.29" until sync corrected it
 * (Alex, Telegram, 2026-06). The fix mirrors the confirmed-row formula:
 * net debit = Σ(our input capacities) − Σ(our spendable change outputs).
 */
class OutgoingAmountTest {

    private val ckb = 100_000_000L

    @Test
    fun `transfer with change shows amount sent plus fee, not the change`() {
        // inputs 167,950.29; recipient 150,000 (not ours); change 17,950.29 (ours)
        val out = computeOutgoingShannons(
            inputCapacities = listOf(167_950_29000000L),
            outputs = listOf(
                OutgoingOutput(150_000 * ckb, isOurs = false, isTyped = false),
                OutgoingOutput(17_950_29000000L, isOurs = true, isTyped = false),
            ),
        )
        // 167,950.29 - 17,950.29 = 150,000.00
        assertEquals(150_000 * ckb, out)
    }

    @Test
    fun `send-all with no change equals the inputs`() {
        val out = computeOutgoingShannons(
            inputCapacities = listOf(150_000 * ckb),
            outputs = listOf(OutgoingOutput(150_000 * ckb, isOurs = false, isTyped = false)),
        )
        assertEquals(150_000 * ckb, out)
    }

    @Test
    fun `self-send consolidation nets to zero`() {
        val out = computeOutgoingShannons(
            inputCapacities = listOf(100 * ckb),
            outputs = listOf(OutgoingOutput(100 * ckb, isOurs = true, isTyped = false)),
        )
        assertEquals(0L, out)
    }

    @Test
    fun `dao deposit counts the typed self-output as leaving spendable`() {
        // fee inputs 10,300; DAO cell 10,200 (ours, typed) + change 99 (ours)
        val out = computeOutgoingShannons(
            inputCapacities = listOf(10_300 * ckb),
            outputs = listOf(
                OutgoingOutput(10_200 * ckb, isOurs = true, isTyped = true),
                OutgoingOutput(99 * ckb, isOurs = true, isTyped = false),
            ),
        )
        // only the plain change (99) is subtracted → 10,201 (deposit + fee)
        assertEquals(10_201 * ckb, out)
    }

    // recipientOutgoingShannons: the output-only fallback for sendTransaction,
    // which has no access to input capacities (DAO unlock, failed-send retry).
    // Sums outputs NOT locked to us — the recipient amount.

    @Test
    fun `recipient-only sums non-ours outputs, ignoring change`() {
        val out = recipientOutgoingShannons(
            listOf(
                OutgoingOutput(150_000 * ckb, isOurs = false, isTyped = false),
                OutgoingOutput(17_950_29000000L, isOurs = true, isTyped = false),
            ),
        )
        assertEquals(150_000 * ckb, out)
    }

    @Test
    fun `recipient-only is zero when every output returns to us`() {
        // DAO unlock returns funds to self; no recipient leg. Shows 0 briefly,
        // then the synced row reclassifies it as dao_unlock (incoming).
        val out = recipientOutgoingShannons(
            listOf(OutgoingOutput(10_500 * ckb, isOurs = true, isTyped = false)),
        )
        assertEquals(0L, out)
    }

    @Test
    fun `never negative`() {
        val out = computeOutgoingShannons(
            inputCapacities = listOf(10 * ckb),
            outputs = listOf(OutgoingOutput(50 * ckb, isOurs = true, isTyped = false)),
        )
        assertEquals(0L, out)
    }

    // ---------------------------------------------------------------------
    // computeFeeShannons (#497): the "Network fee" row on the transaction
    // detail sheet. Shapes below mirror the two real call sites -- the send
    // path's reserved cells, and the confirmed path's light-client
    // interaction walk.
    // ---------------------------------------------------------------------

    /** Pending row, planned fee: one input, recipient + change, 0.00001 CKB. */
    @Test
    fun `pending send computes the planned fee from reserved inputs`() {
        val fee = computeFeeShannons(
            resolvedInputs = listOf(1_000 * ckb),
            declaredInputCount = 1,
            outputCapacities = listOf(100 * ckb, 900 * ckb - 1_000L),
        )
        assertEquals(1_000L, fee)
    }

    @Test
    fun `confirmed outgoing computes the fee across every resolved input`() {
        // A fragmented wallet spends several cells; all of them are ours, so
        // the walk resolves all of them and the fee is exact.
        val fee = computeFeeShannons(
            resolvedInputs = listOf(61 * ckb, 61 * ckb, 61 * ckb),
            declaredInputCount = 3,
            outputCapacities = listOf(150 * ckb, 33 * ckb - 12_345L),
        )
        assertEquals(12_345L, fee)
    }

    @Test
    fun `incoming resolves no inputs so the fee is unknown`() {
        // Every input belongs to the sender: the walk yields no input
        // interaction, so there is nothing to subtract from. Null, not 0 --
        // the sheet hides the row for an incoming tx anyway, and a 0 here
        // would read as "this transaction was free".
        val fee = computeFeeShannons(
            resolvedInputs = emptyList(),
            declaredInputCount = 2,
            outputCapacities = listOf(100 * ckb),
        )
        assertNull(fee)
    }

    @Test
    fun `partially resolved inputs are unknown rather than under-counted`() {
        // Checkpoint-synced wallet: one input cell predates the sync window,
        // so the light client never indexed it. Scoring the two we do have
        // would report a fee short by the whole missing input.
        val fee = computeFeeShannons(
            resolvedInputs = listOf(61 * ckb, 61 * ckb),
            declaredInputCount = 3,
            outputCapacities = listOf(150 * ckb),
        )
        assertNull(fee)
    }

    @Test
    fun `a tx with no declared inputs is unknown`() {
        assertNull(
            computeFeeShannons(
                resolvedInputs = emptyList(),
                declaredInputCount = 0,
                outputCapacities = listOf(100 * ckb),
            )
        )
    }

    @Test
    fun `an inconsistent set that nets negative is unknown, never a negative fee`() {
        assertNull(
            computeFeeShannons(
                resolvedInputs = listOf(10 * ckb),
                declaredInputCount = 1,
                outputCapacities = listOf(50 * ckb),
            )
        )
    }

    @Test
    fun `a genuinely zero fee is zero, distinct from unknown`() {
        assertEquals(
            0L,
            computeFeeShannons(
                resolvedInputs = listOf(100 * ckb),
                declaredInputCount = 1,
                outputCapacities = listOf(100 * ckb),
            )
        )
    }
}
