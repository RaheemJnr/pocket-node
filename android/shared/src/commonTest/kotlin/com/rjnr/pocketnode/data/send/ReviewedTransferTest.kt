package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.transaction.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlin.test.*

class ReviewedTransferTest {
    private val sender = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val destination = sender.copy(args = "0x" + "bb".repeat(20))
    private val other = sender.copy(args = "0x" + "cc".repeat(20))
    private val from = AddressUtils.encode(sender, NetworkType.TESTNET)
    private val to = AddressUtils.encode(destination, NetworkType.TESTNET)
    private val cells = listOf(Cell(OutPoint("0x" + "11".repeat(32), "0x0"), "0x${300_00000000L.toString(16)}", "0x1", sender))
    private val builder = TransactionBuilder(NetworkValidator())
    private val recipients = listOf(RecipientOutput(to, 100_00000000L))
    private fun review(outputs: List<RecipientOutput> = recipients) = ReviewedTransfer("wallet", from, NetworkType.TESTNET, outputs, 1000, 1)
    private fun prepared(outputs: List<RecipientOutput> = recipients) = builder.prepareMultiTransfer(from, outputs, cells, NetworkType.TESTNET)

    @Test fun acceptsExactRecipientAndSenderChange() { review().validatePrepared(prepared()) }
    @Test fun sameFeeDifferentRecipientOrAmountIsRejected() {
        for (changed in listOf(listOf(RecipientOutput(AddressUtils.encode(other, NetworkType.TESTNET), 100_00000000L)), listOf(RecipientOutput(to, 150_00000000L)))) {
            assertEquals(1000, builder.planTransfer(changed, cells).feeShannons)
            assertFailsWith<IllegalArgumentException> { review().validatePrepared(prepared(changed)) }
        }
    }
    @Test fun ownsCopiesOfRecipientLists() {
        val mutable = recipients.toMutableList()
        val reviewed = review(mutable)
        mutable[0] = RecipientOutput(to, 150_00000000L)
        runCatching { (reviewed.recipients as? MutableList<RecipientOutput>)?.clear() }
        reviewed.validatePrepared(prepared())
        assertEquals(100_00000000L, reviewed.amountShannons)
    }
    @Test fun rejectsModifiedChangeTypeDataFeeOrderCountOrInputs() {
        val original = prepared()
        val tx = original.transaction
        val variants = listOf(
            tx.copy(cellOutputs = tx.cellOutputs.reversed()),
            tx.copy(cellOutputs = tx.cellOutputs + tx.cellOutputs.last()),
            tx.copy(cellOutputs = tx.cellOutputs.mapIndexed { i, o -> if (i == 1) o.copy(lock = other) else o }),
            tx.copy(cellOutputs = tx.cellOutputs.mapIndexed { i, o -> if (i == 0) o.copy(type = sender) else o }),
            tx.copy(outputsData = listOf("0x01", "0x")),
            tx.copy(cellOutputs = tx.cellOutputs.mapIndexed { i, o -> if (i == 1) o.copy(capacity = "0x${(o.capacity.removePrefix("0x").toLong(16) - 1).toString(16)}") else o }),
            tx.copy(cellInputs = tx.cellInputs + tx.cellInputs),
            tx.copy(cellInputs = listOf(CellInput(previousOutput = OutPoint("missing", "0x0")))),
            tx.copy(cellDeps = listOf(CellDep.SECP256K1_MAINNET)),
        )
        variants.forEach { assertFailsWith<IllegalArgumentException> { review().validatePrepared(original.copy(transaction = it)) } }
        assertFailsWith<IllegalArgumentException> { review().validatePrepared(original.copy(selectedCells = cells.map { it.copy(lock = other) })) }
    }
    @Test fun preservesOrderedBulkAndExactMaxOutputs() {
        val bulk = listOf(RecipientOutput(to, 100_00000000L), RecipientOutput(from, 100_00000000L))
        review(bulk).validatePrepared(prepared(bulk))
        val max = listOf(RecipientOutput(to, 299_99999000L))
        val maxPrepared = prepared(max)
        assertEquals(1, maxPrepared.transaction.cellOutputs.size)
        review(max).validatePrepared(maxPrepared)
    }
}
