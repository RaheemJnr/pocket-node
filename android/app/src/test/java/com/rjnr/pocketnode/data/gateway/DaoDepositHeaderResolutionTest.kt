package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.gateway.models.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic committed model fixtures; only JNI/cache reads and native arithmetic are doubled. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [DaoUnlockShadowLightClientNative::class],
    instrumentedPackages = ["com.nervosnetwork.ckblightclient"])
class DaoDepositHeaderResolutionTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val shadow get() = DaoUnlockShadowLightClientNative
    private val lock = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
    private val dao = DaoConstants.DAO_TYPE_SCRIPT
    private val phaseHash = hash("aa")
    private val inputA = hash("a1")
    private val inputB = hash("b1")
    private val headerA = header(hash("d1"), 100, "0x70800000000064", "0x10000000")
    private val headerB = header(hash("d2"), 200, "0x708000000000c8", "0x20000000")
    private val withdrawHeader = header(hash("d3"), 300, "0x7080000000012c", "0x40000000")
    private val unrelated = header(hash("d4"), 50, "0x70800000000032", "0x8000000")
    private val capacityA = 100_000_000_000L
    private val capacityB = 200_000_000_000L
    private val outputA = output(capacityA)
    private val outputB = output(capacityB)
    private val cellA = cell(0, outputA, "0x6400000000000000")
    private val cellB = cell(1, outputB, "0xc800000000000000")
    private val headers = mutableMapOf<String, JniHeaderView>()
    private lateinit var reader: DaoDepositReader
    private lateinit var phase: JniTransactionWithStatus

    @Before fun setup() {
        reset()
        listOf(headerA, headerB, withdrawHeader, unrelated).forEach { headers[it.hash] = it }
        canonical(headerA); canonical(headerB); canonical(withdrawHeader)
        phase = transaction(phaseHash, withdrawHeader.hash,
            listOf(headerB.hash, headerA.hash),
            listOf(CellInput(previousOutput = OutPoint(inputA, "0x0")),
                CellInput(previousOutput = OutPoint(inputB, "0x0"))),
            listOf(outputA, outputB), listOf(cellA.outputData!!, cellB.outputData!!))
        store(phase)
        store(transaction(inputA, headerA.hash, outputs = listOf(outputA), data = listOf("0x0000000000000000")))
        store(transaction(inputB, headerB.hash, outputs = listOf(outputB), data = listOf("0x0000000000000000")))
        val resolver = mockk<DaoHeaderResolver>()
        coEvery { resolver.getBlockHashForCell(phaseHash) } returns withdrawHeader.hash
        coEvery { resolver.getOrFetchHeader(any(), any(), any()) } answers { headers[firstArg()] }
        reader = DaoDepositReader(json, resolver, NoopLogger)
    }

    @After fun reset() {
        shadow.transactionsByHash.clear(); shadow.headersByNumber.clear()
        shadow.transactionOverride = null; shadow.chainStatus = null; shadow.cellsPage = null
        shadow.tipHeader = null; shadow.maxWithdrawCalls.clear(); shadow.transactionQueries.clear()
        headers.clear()
    }

    // Replacing per-cell selection with first() assigns B's epoch/DAO to A.
    @Test fun `multiple deposits resolve their own encoded heights with reordered deps`() = runTest {
        val deposits = read(cellA, cellB)
        assertEquals(listOf(100L, 200L), deposits.map { it.depositBlockNumber })
        assertEquals(listOf(headerA.hash, headerB.hash), deposits.map { it.depositBlockHash })
        assertEquals(listOf(EpochInfo(100, 0, 28800), EpochInfo(200, 0, 28800)), deposits.map { it.depositEpoch })
        assertEquals(listOf(0x10000000L, 0x20000000L), deposits.map { it.depositTimestamp })
        assertEquals(listOf(headerA.dao, headerB.dao), shadow.maxWithdrawCalls.map { it.depositDao })
        assertTrue(deposits.all { it.unlockEpoch != null && it.compensation == 1_000L })
    }

    @Test fun `unrelated first dependency is ignored`() = runTest {
        updatePhase(phase.transaction!!.copy(headerDeps = listOf(unrelated.hash, headerA.hash, headerB.hash)))
        assertEquals(headerA.hash, read(cellA).single().depositBlockHash)
    }

    @Test fun `valid standard single deposit resolves unchanged`() = runTest {
        updatePhase(phase.transaction!!.copy(headerDeps = listOf(headerA.hash),
            inputs = phase.transaction!!.inputs.take(1), outputs = listOf(outputA), outputsData = listOf(cellA.outputData!!)))
        val deposit = read(cellA).single()
        assertEquals(headerA.hash, deposit.depositBlockHash)
        assertEquals(1_000L, deposit.compensation)
        assertNotNull(deposit.unlockEpoch)
        assertEquals(DaoConstants.DEPOSIT_OCCUPIED_SHANNONS, shadow.maxWithdrawCalls.single().occupied)
    }

    @Test fun `missing matching dependency stays unresolved`() = runTest {
        updatePhase(phase.transaction!!.copy(headerDeps = listOf(headerB.hash)))
        unresolved(read(cellA).single())
    }

    @Test fun `missing fetched header stays unresolved`() = runTest {
        headers.remove(headerA.hash)
        unresolved(read(cellA).single())
    }

    @Test fun `missing canonical evidence is retryable`() = runTest {
        shadow.headersByNumber.remove("0x64")
        unresolved(read(cellA).single())
        canonical(headerA)
        assertEquals(headerA.hash, read(cellA).single().depositBlockHash)
    }

    @Test fun `same height fork dependency cannot replace canonical header`() = runTest {
        val fork = headerA.copy(hash = hash("ff"))
        headers[fork.hash] = fork
        updatePhase(phase.transaction!!.copy(headerDeps = listOf(fork.hash)))
        unresolved(read(cellA).single())
    }

    @Test fun `canonical lookup with wrong height stays unresolved`() = runTest {
        shadow.headersByNumber["0x64"] = json.encodeToString(headerB)
        unresolved(read(cellA).single())
    }

    @Test fun `header fetched by hash must return requested identity`() = runTest {
        headers[headerA.hash] = headerA.copy(hash = hash("ff"))
        unresolved(read(cellA).single())
    }

    @Test fun `header fetched by hash must return encoded height`() = runTest {
        headers[headerA.hash] = headerA.copy(number = "0x65")
        unresolved(read(cellA).single())
    }

    @Test fun `same hash conflicting cached fields stay unresolved`() = runTest {
        headers[headerA.hash] = headerA.copy(epoch = headerB.epoch)
        unresolved(read(cellA).single())
    }

    @Test fun `unavailable old parent does not block verified phase one resolution`() = runTest {
        shadow.transactionsByHash.remove(inputA)
        assertEquals(headerA.hash, read(cellA).single().depositBlockHash)
    }

    @Test fun `input on different block does not prove deposit linkage`() = runTest {
        store(transaction(inputA, headerB.hash, outputs = listOf(outputA), data = listOf("0x0000000000000000")))
        unresolved(read(cellA).single())
    }

    @Test fun `typed input with nonzero data does not prove deposit linkage`() = runTest {
        store(transaction(inputA, headerA.hash, outputs = listOf(outputA), data = listOf(cellA.outputData!!)))
        unresolved(read(cellA).single())
    }

    @Test fun `plain fee input cannot prove deposit linkage`() = runTest {
        store(transaction(inputA, headerA.hash, outputs = listOf(outputA.copy(type = null)), data = listOf("0x0000000000000000")))
        unresolved(read(cellA).single())
    }

    @Test fun `phase transaction wrong identity stays unresolved`() = runTest {
        shadow.transactionsByHash[phaseHash] = json.encodeToString(phase.copy(transaction = phase.transaction!!.copy(hash = hash("ff"))))
        unresolved(read(cellA).single())
    }

    @Test fun `uncommitted phase transaction stays unresolved`() = runTest {
        store(phase.copy(txStatus = JniTxStatus("pending", withdrawHeader.hash)))
        unresolved(read(cellA).single())
    }

    @Test fun `phase output data must agree with scanned cell`() = runTest {
        updatePhase(phase.transaction!!.copy(outputsData = listOf(cellB.outputData!!, cellB.outputData!!)))
        unresolved(read(cellA).single())
    }

    @Test fun `withdraw header must agree with scanned block height`() = runTest {
        headers[withdrawHeader.hash] = withdrawHeader.copy(number = "0x12d")
        unresolved(read(cellA).single())
    }

    @Test fun `different parent lock with same size remains valid`() = runTest {
        val changed = outputA.copy(lock = lock.copy(args = "0x" + "22".repeat(20)))
        store(transaction(inputA, headerA.hash, outputs = listOf(changed), data = listOf("0x0000000000000000")))
        assertEquals(headerA.hash, read(cellA).single().depositBlockHash)
    }

    @Test fun `identical deposits at one height resolve by corresponding input index`() = runTest {
        store(transaction(inputB, headerA.hash, outputs = listOf(outputA), data = listOf("0x0000000000000000")))
        updatePhase(phase.transaction!!.copy(outputs = listOf(outputA, outputA),
            outputsData = listOf(cellA.outputData!!, cellA.outputData!!), headerDeps = listOf(headerA.hash)))
        val second = cell(1, outputA, cellA.outputData!!)
        assertEquals(listOf(headerA.hash, headerA.hash), read(cellA, second).map { it.depositBlockHash })
    }

    @Test fun `normal fee input and change preceding two DAO cells preserve global pairing`() = runTest {
        val feeHash = hash("f1")
        val fee = output(10_000_000_000L).copy(type = null)
        store(transaction(feeHash, unrelated.hash, outputs = listOf(fee), data = listOf("0x")))
        updatePhase(phase.transaction!!.copy(
            inputs = listOf(CellInput(previousOutput = OutPoint(feeHash, "0x0"))) + phase.transaction!!.inputs,
            outputs = listOf(fee.copy(capacity = "0x2540be018"), outputA, outputB),
            outputsData = listOf("0x", cellA.outputData!!, cellB.outputData!!)))
        assertEquals(listOf(headerA.hash, headerB.hash),
            read(cell(1, outputA, cellA.outputData!!), cell(2, outputB, cellB.outputData!!)).map { it.depositBlockHash })
    }

    @Test fun `cached-only row corrects stale first dependency using actual encoded output`() = runTest {
        val stale = cached().copy(depositBlockHash = headerB.hash, depositEpochHex = headerB.epoch)
        val checked = reader.revalidateCachedWithdrawing(stale, EpochInfo(1000, 0, 1800), NetworkType.TESTNET)
        assertEquals(100L, checked.depositBlockNumber)
        assertEquals(headerA.hash, checked.depositBlockHash)
        assertEquals(headerA.epoch, checked.depositEpochHex)
        assertEquals(1_000L, checked.compensation)
        assertNotNull(checked.unlockEpochHex)
    }

    @Test fun `cached-only missing phase one clears stale metadata and remains retryable`() = runTest {
        shadow.transactionsByHash.remove(phaseHash)
        val checked = reader.revalidateCachedWithdrawing(cached(), null, NetworkType.TESTNET)
        assertEquals("", checked.depositBlockHash); assertNull(checked.depositEpochHex)
        assertNull(checked.unlockEpochHex); assertEquals(0L, checked.compensation)
        assertEquals(0L, checked.depositTimestamp); assertEquals("LOCKED", checked.status)
        assertEquals(phaseHash, checked.txHash); assertEquals(300L, checked.withdrawBlockNumber)
        store(phase)
        assertEquals(headerA.hash, reader.revalidateCachedWithdrawing(checked, null, NetworkType.TESTNET).depositBlockHash)
    }

    @Test fun `cached-only missing canonical proof clears stale unlock metadata`() = runTest {
        shadow.headersByNumber.remove("0x64")
        val checked = reader.revalidateCachedWithdrawing(cached(), null, NetworkType.TESTNET)
        assertEquals("", checked.depositBlockHash); assertNull(checked.unlockEpochHex)
        assertEquals(0L, checked.compensation)
    }

    private fun cached() = com.rjnr.pocketnode.data.database.entity.DaoCellEntity(
        txHash = phaseHash, index = "0x0", capacity = capacityA, status = "UNLOCKABLE",
        depositBlockNumber = 200, depositBlockHash = headerB.hash, depositEpochHex = headerB.epoch,
        withdrawBlockNumber = 300, withdrawBlockHash = withdrawHeader.hash, withdrawEpochHex = withdrawHeader.epoch,
        compensation = 777, unlockEpochHex = "0x70800000000001", depositTimestamp = 99,
        network = "TESTNET", walletId = "wallet", lastUpdatedAt = 1)

    @Test fun `phase one missing stays unresolved`() = runTest {
        shadow.transactionsByHash.remove(phaseHash)
        unresolved(read(cellA).single())
    }

    @Test fun `phase one wrong block association stays unresolved`() = runTest {
        store(phase.copy(txStatus = JniTxStatus("committed", headerB.hash)))
        unresolved(read(cellA).single())
    }

    @Test fun `withdraw block must also match current canonical identity`() = runTest {
        canonical(withdrawHeader.copy(hash = hash("ff")))
        unresolved(read(cellA).single())
    }

    @Test fun `locally unknown parent envelope permits verified phase one resolution`() = runTest {
        shadow.transactionsByHash[inputA] = json.encodeToString(JniTransactionWithStatus(txStatus = JniTxStatus("unknown")))
        assertEquals(headerA.hash, read(cellA).single().depositBlockHash)
    }

    private fun unresolved(deposit: DaoDeposit) {
        assertEquals(100L, deposit.depositBlockNumber)
        assertEquals("", deposit.depositBlockHash)
        assertNull(deposit.depositEpoch); assertNull(deposit.unlockEpoch)
        assertEquals(0L, deposit.depositTimestamp); assertEquals(0L, deposit.compensation)
        assertEquals(DaoCellStatus.LOCKED, deposit.status)
        assertEquals(0.0, deposit.apc, 0.0)
        assertTrue(shadow.maxWithdrawCalls.isEmpty())
    }

    private suspend fun read(vararg cells: JniCell): List<DaoDeposit> {
        shadow.cellsPage = json.encodeToString(JniPagination(cells.toList(), null))
        return reader.list(lock, EpochInfo.fromHex("0x708000000003e8"), NetworkType.TESTNET)
    }
    private fun canonical(h: JniHeaderView) { shadow.headersByNumber[h.number] = json.encodeToString(h) }
    private fun store(tx: JniTransactionWithStatus) { shadow.transactionsByHash[tx.transaction!!.hash] = json.encodeToString(tx) }
    private fun updatePhase(tx: JniTransactionView) { phase = phase.copy(transaction = tx); store(phase) }
    private fun transaction(hash: String, block: String, deps: List<String> = emptyList(),
        inputs: List<CellInput> = emptyList(), outputs: List<CellOutput> = emptyList(), data: List<String> = emptyList()) =
        JniTransactionWithStatus(JniTransactionView(hash, "0x0", emptyList(), deps, inputs, outputs, data, emptyList()),
            txStatus = JniTxStatus("committed", block))
    private fun output(capacity: Long) = CellOutput("0x" + capacity.toString(16), lock, dao)
    private fun cell(index: Int, output: CellOutput, data: String) = JniCell(output, data,
        OutPoint(phaseHash, "0x" + index.toString(16)), "0x12c", "0x1")
    private fun hash(byte: String) = "0x" + byte.repeat(32)
    private fun header(hash: String, number: Int, epoch: String, timestamp: String) = JniHeaderView(
        hash, "0x" + number.toString(16), epoch, timestamp, hash("e0"), hash("e1"), hash("e2"), hash("e3"), hash, "0x0")
}
