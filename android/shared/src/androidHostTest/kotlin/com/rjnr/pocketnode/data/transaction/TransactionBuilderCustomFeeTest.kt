package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.ReviewedTransfer
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import org.junit.Assert.*
import org.junit.Test

class TransactionBuilderCustomFeeTest {
    private val builder = TransactionBuilder(NetworkValidator())
    private val network = NetworkType.TESTNET
    private val sender = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val from = AddressUtils.encode(sender, network)
    private val ckb = 100_000_000L
    private fun recipients(count: Int, argsBytes: Int) = List(count) { index ->
        val script = sender.copy(codeHash = "0x" + "42".repeat(32), hashType = "data2",
            args = "0x" + (index + 1).toString(16).padStart(2, '0').repeat(argsBytes))
        RecipientOutput(AddressUtils.encode(script, network), maxOf(100L, 41L + argsBytes) * ckb)
    }
    private fun cells(total: Long) = listOf(Cell(OutPoint("0x" + "11".repeat(32), "0x0"),
        "0x${total.toString(16)}", "0x1", sender, data = "0x"))
    private fun signed(outputs: List<RecipientOutput>, total: Long): Pair<Transaction, Long> {
        val available = cells(total)
        val plan = builder.planTransfer(outputs, available)
        val prepared = builder.prepareMultiTransfer(from, outputs, available, network)
        // A review can currently accept the same underpriced fee; keep its exact-byte guard in the path.
        ReviewedTransfer("wallet", from, network, outputs, plan.feeShannons, 1).validatePrepared(prepared)
        val tx = builder.signTransaction(prepared.transaction, ByteArray(32) { 0x11 }, prepared.selectedCells.size)
        val paid = total - tx.cellOutputs.sumOf { it.capacity.removePrefix("0x").toLong(16) }
        assertEquals(plan.feeShannons, paid)
        return tx to paid
    }
    private fun assertCovered(outputs: List<RecipientOutput>, total: Long, expectedOutputs: Int) {
        val (tx, paid) = signed(outputs, total)
        assertEquals(expectedOutputs, tx.cellOutputs.size)
        val size = builder.serializedSizeWithWitnesses(tx)
        val minimum = (size.toLong() * TransactionBuilder.FEE_RATE + 999) / 1000
        println("recipients=${outputs.size}, outputs=${tx.cellOutputs.size}, bytes=$size, paid=$paid, minimum=$minimum")
        assertTrue("paid $paid must cover actual signed Molecule size $size (minimum $minimum)", paid >= minimum)
    }
    @Test fun `25 recipients with 40 byte args pay for signed bytes and change`() {
        val outputs = recipients(25, 40)
        assertCovered(outputs, outputs.sumOf { it.amountShannons } + 1000 * ckb, 26)
    }
    @Test fun `25 recipients with 40 byte args pay for signed bytes without change`() {
        val outputs = recipients(25, 40)
        val amount = outputs.sumOf { it.amountShannons }
        val fee = builder.estimateTransferFeeForScripts(1, outputs.map { AddressUtils.parseAddress(it.address)!! })
        assertCovered(outputs, amount + fee, 25)
    }
    @Test fun `single large custom lock pays for signed bytes and change`() {
        val outputs = recipients(1, 1024)
        assertCovered(outputs, outputs.single().amountShannons + 1000 * ckb, 2)
    }
    @Test fun `max to large custom lock pays for signed bytes without change`() {
        val total = 2000 * ckb
        val outputs = recipients(1, 1024).map { it.copy(amountShannons = builder.calculateMaxSendable(cells(total), it.address)) }
        assertCovered(outputs, total, 1)
    }
    @Test fun `actual serialization grows 500 bytes for 25 extra 20 byte args`() {
        val total = 5000 * ckb
        val (standard, standardFee) = signed(recipients(25, 20), total)
        val (custom, _) = signed(recipients(25, 40), total)
        assertEquals(500, builder.serializedSizeWithWitnesses(custom) - builder.serializedSizeWithWitnesses(standard))
        assertEquals(builder.estimateTransferFee(1, 26), standardFee)
        assertTrue(standardFee >= builder.serializedSizeWithWitnesses(standard))
        val small = recipients(1, 20)
        assertEquals(1000L, signed(small, total).second)
    }
    @Test fun `prepared fee guard rejects a fabricated underpriced custom output`() {
        val outputs = recipients(1, 20)
        val prepared = builder.prepareMultiTransfer(from, outputs, cells(3000 * ckb), network)
        val custom = AddressUtils.parseAddress(recipients(1, 1024).single().address)!!
        val forged = prepared.copy(transaction = prepared.transaction.copy(cellOutputs =
            prepared.transaction.cellOutputs.mapIndexed { index, output ->
                if (index == 0) output.copy(lock = custom) else output
            }))
        val error = runCatching { builder.validateTransferFee(forged) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("serialized size"))
    }
    @Test fun `lower actual fee creates minimum change and reprices that output`() {
        val outputs = recipients(25, 40)
        val scripts = outputs.map { AddressUtils.parseAddress(it.address)!! } + sender
        val fee = builder.estimateTransferFeeForScripts(1, scripts)
        val total = outputs.sumOf { it.amountShannons } + TransactionBuilder.MIN_CELL_CAPACITY + fee
        val plan = builder.planTransfer(outputs, cells(total))
        assertEquals(TransactionBuilder.MIN_CELL_CAPACITY, plan.changeShannons)
        assertCovered(outputs, total, 26)
    }
    @Test fun `selection gathers another cell when actual custom fee exceeds default allowance`() {
        val outputs = recipients(1, 120000)
        val amount = outputs.single().amountShannons
        val first = cells(amount + TransactionBuilder.DEFAULT_FEE).single()
        // Smallest-first: make the first cell the larger-script funding cell but still smaller than the next.
        val second = first.copy(outPoint = first.outPoint.copy(index = "0x1"),
            capacity = "0x${(amount + 1000 * ckb).toString(16)}")
        val prepared = builder.prepareMultiTransfer(from, outputs, listOf(first, second), network)
        assertEquals(2, prepared.selectedCells.size)
        builder.validateTransferFee(prepared)
    }

    @Test fun `actual change lock size and draft estimate agree with prepared fee`() {
        val source = sender.copy(codeHash = "0x" + "43".repeat(32), args = "0x" + "cc".repeat(1024))
        val sourceAddress = AddressUtils.encode(source, network)
        val outputs = recipients(1, 40)
        val available = cells(3000 * ckb).map { it.copy(lock = source) }
        val plan = builder.planTransfer(outputs, available)
        val prepared = builder.prepareMultiTransfer(sourceAddress, outputs, available, network)
        assertEquals(source, prepared.transaction.cellOutputs.last().lock)
        assertEquals(builder.estimateTransferFeeForDraft(1, outputs.map { it.address }, sourceAddress, true), plan.feeShannons)
        assertTrue(plan.feeShannons > 1000)
    }
    @Test fun `count arithmetic does not wrap at Int limits`() {
        assertTrue(builder.estimateTransferFee(Int.MAX_VALUE, Int.MAX_VALUE) > Int.MAX_VALUE.toLong())
    }
    @Test fun `large custom fee fails closed when all cells are insufficient`() {
        val outputs = recipients(1, 120000)
        val insufficient = cells(outputs.single().amountShannons + TransactionBuilder.DEFAULT_FEE)
        assertTrue(runCatching { builder.prepareMultiTransfer(from, outputs, insufficient, network) }.isFailure)
    }

}
