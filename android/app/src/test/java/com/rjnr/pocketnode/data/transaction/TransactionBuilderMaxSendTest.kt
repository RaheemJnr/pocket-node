package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * calculateMaxSendable must use pure integer math (no Double precision loss)
 * and must charge fee for ALL spendable cells as inputs — sending max consumes
 * every cell, so the 1-input assumption underestimated the fee on fragmented
 * wallets and the resulting send failed with insufficient funds (#321).
 */
@RunWith(RobolectricTestRunner::class)
class TransactionBuilderMaxSendTest {

    private val builder = TransactionBuilder(NetworkValidator())

    private val lock = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = "0x0011223344556677889900112233445566778899"
    )

    private fun cell(capacityShannons: Long, index: Int = 0) = Cell(
        outPoint = OutPoint(txHash = "0x" + "ab".repeat(32), index = "0x${index.toString(16)}"),
        capacity = "0x" + capacityShannons.toString(16),
        blockNumber = "0x1",
        lock = lock
    )

    @Test
    fun `single cell max is capacity minus 1-input fee`() {
        val cells = listOf(cell(100_00000000L))
        val expected = 100_00000000L - builder.estimateTransferFee(inputCount = 1, outputCount = 1)
        assertEquals(expected, builder.calculateMaxSendable(cells))
    }

    @Test
    fun `fragmented wallet charges fee for every input`() {
        // 30 cells pushes estimated tx size past the MIN_FEE clamp so the
        // per-input fee actually differentiates from the 1-input estimate.
        val cells = (0 until 30).map { cell(70_00000000L, index = it) }
        val sum = 30 * 70_00000000L
        val expected = sum - builder.estimateTransferFee(inputCount = 30, outputCount = 1)
        assertEquals(expected, builder.calculateMaxSendable(cells))
    }

    @Test
    fun `no cells means zero max`() {
        assertEquals(0L, builder.calculateMaxSendable(emptyList()))
    }

    @Test
    fun `fee exceeding total clamps to zero`() {
        val cells = listOf(cell(500L))
        assertEquals(0L, builder.calculateMaxSendable(cells))
    }

    @Test
    fun `no precision loss above the Double 53-bit mantissa`() {
        // 100M CKB in one cell — above ~90M CKB a Double round-trip corrupts
        // the shannon amount; integer math must be exact.
        val big = 100_000_000_00000000L
        val cells = listOf(cell(big))
        val expected = big - builder.estimateTransferFee(inputCount = 1, outputCount = 1)
        assertEquals(expected, builder.calculateMaxSendable(cells))
    }

    @Test
    fun `malformed capacity cell is skipped entirely`() {
        // Malformed node data: cell drops out of both the sum and the input
        // count — it could not be spent by buildTransfer anyway.
        val good = cell(100_00000000L)
        val bad = good.copy(capacity = "0xZZ")
        val expected = 100_00000000L - builder.estimateTransferFee(inputCount = 1, outputCount = 1)
        assertEquals(expected, builder.calculateMaxSendable(listOf(good, bad)))
    }
}
