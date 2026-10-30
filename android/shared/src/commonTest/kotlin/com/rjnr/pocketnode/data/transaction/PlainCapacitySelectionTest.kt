package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.ReviewedTransfer
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Synthetic cells: only the two observed-empty, source-locked cells may fund generic sends. */
class PlainCapacitySelectionTest {
    private val builder = TransactionBuilder(NetworkValidator())
    private val source = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val other = source.copy(args = "0x" + "bb".repeat(20))
    private val from = AddressUtils.encode(source, NetworkType.TESTNET)
    private val to = AddressUtils.encode(other, NetworkType.TESTNET)
    private fun cell(id: Int, capacity: Long = 300_00000000L, data: String = "0x") = Cell(
        OutPoint("0x" + id.toString(16).padStart(64, '0'), "0x0"),
        "0x${capacity.toString(16)}", "0x1", source, data = data,
    )
    private val good = listOf(cell(1), cell(2))
    private val protected = listOf(
        cell(3, 61_00000000L, "0x01"), cell(4, 62_00000000L, "0x00"),
        cell(5, 63_00000000L, ""), cell(6, 64_00000000L).copy(type = other),
    )
    private val wrongLock = cell(7, 65_00000000L).copy(lock = other)
    private fun recipients(bulk: Boolean) = if (bulk) listOf(RecipientOutput(to, 100_00000000L), RecipientOutput(from, 100_00000000L))
        else listOf(RecipientOutput(to, 100_00000000L))

    @Test fun singleAndBulkPreparedInputsPreserveDataAndOtherLocks() {
        for (bulk in listOf(false, true)) {
            val outputs = recipients(bulk)
            val prepared = builder.prepareMultiTransfer(from, outputs, protected + wrongLock + good, NetworkType.TESTNET)
            assertEquals(listOf(good.first().outPoint), prepared.transaction.cellInputs.map { it.previousOutput })
            assertEquals(listOf(good.first()), prepared.selectedCells)
            ReviewedTransfer("synthetic", from, NetworkType.TESTNET, outputs, 1000, 1).validatePrepared(prepared)
        }
    }

    @Test fun maxAmountAndAllChosenInputsUseOnlyObservedEmptyCells() {
        // 600 CKB minus the 1000-shannon fee for two inputs and one output.
        val max = builder.calculateMaxSendable(protected + good)
        assertEquals(599_99999000L, max)
        val prepared = builder.prepareMultiTransfer(from, listOf(RecipientOutput(to, max)), protected + wrongLock + good, NetworkType.TESTNET)
        assertEquals(good.map { it.outPoint }, prepared.transaction.cellInputs.map { it.previousOutput })
        assertEquals(1, prepared.transaction.cellOutputs.size)
        assertEquals("0x${max.toString(16)}", prepared.transaction.cellOutputs.single().capacity)
    }

    @Test fun dataOnlyWalletCannotFundAPlanOrMax() {
        assertEquals(0L, builder.calculateMaxSendable(protected))
        assertFailsWith<IllegalStateException> { builder.planTransfer(recipients(false), protected) }
    }

    @Test fun missingDataInCellAndBridgePayloadIsNotEvidenceOfEmptyData() {
        val json = Json { encodeDefaults = true }
        val omitted = """{"out_point":{"tx_hash":"0x${"11".repeat(32)}","index":"0x0"},"capacity":"0x6fc23ac00","block_number":"0x1","lock":{"code_hash":"${source.codeHash}","hash_type":"type","args":"${source.args}"}}"""
        val decoded = json.decodeFromString<Cell>(omitted)
        val absent = JniCell(CellOutput(good[0].capacity, source), null, good[0].outPoint, "0x1", "0x0").toCell()
        assertEquals(0L, builder.calculateMaxSendable(listOf(decoded, absent)))
        assertFailsWith<IllegalStateException> { builder.prepareMultiTransfer(from, recipients(false), listOf(decoded, absent), NetworkType.TESTNET) }
    }

    @Test fun preparedGuardStillRejectsDataTypeAndUnknownInputs() {
        val outputs = recipients(false)
        val prepared = builder.prepareMultiTransfer(from, outputs, good, NetworkType.TESTNET)
        val review = ReviewedTransfer("synthetic", from, NetworkType.TESTNET, outputs, 1000, 1)
        for (changed in listOf(good[0].copy(data = "0x00"), good[0].copy(data = ""), good[0].copy(type = other))) {
            assertFailsWith<IllegalArgumentException> { review.validatePrepared(prepared.copy(selectedCells = listOf(changed))) }
        }
    }
}
