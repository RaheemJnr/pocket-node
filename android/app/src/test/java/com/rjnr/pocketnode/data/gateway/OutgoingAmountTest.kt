package com.rjnr.pocketnode.data.gateway

import org.junit.Assert.assertEquals
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

    @Test
    fun `never negative`() {
        val out = computeOutgoingShannons(
            inputCapacities = listOf(10 * ckb),
            outputs = listOf(OutgoingOutput(50 * ckb, isOurs = true, isTyped = false)),
        )
        assertEquals(0L, out)
    }
}
