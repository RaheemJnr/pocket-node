package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.transaction.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [DaoUnlockShadowLightClientNative::class], instrumentedPackages = ["com.nervosnetwork.ckblightclient"])
class GapLimitPlainCapacityTest {
    private val source = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val candidate = source.copy(args = "0x" + "bb".repeat(20))
    private val json = Json { encodeDefaults = true }
    private fun cell(id: Int, data: String? = "0x", lock: Script = candidate, type: Script? = null) = JniCell(
        CellOutput("0x${300_00000001L.toString(16)}", lock, type), data,
        OutPoint("0x" + id.toString(16).padStart(64, '0'), "0x0"), "0x1", "0x0")
    @After fun cleanup() { DaoUnlockShadowLightClientNative.cellsPage = null }

    @Test fun sweepPreviewAndChosenTransactionInputsPreserveDataCells() = runTest {
        val safe = cell(1)
        val cells = listOf(safe, cell(2, "0x01"), cell(3, "0x00"), cell(4, ""), cell(5, null),
            cell(6, type = source), cell(7, lock = source))
        DaoUnlockShadowLightClientNative.cellsPage = json.encodeToString(JniPagination(cells, null))
        val database = mockk<AppDatabase>(relaxed = true)
        coEvery { database.subAccountCandidateDao().getForParent("synthetic") } returns listOf(
            SubAccountCandidateEntity("synthetic", "m/44'/309'/0'/0/1", 0, candidate.args,
                SubAccountCandidateEntity.STATE_FOUND, 0))
        val ledger = mockk<LedgerReader>()
        coEvery { ledger.fetchAllSpentOutpoints(any()) } returns mutableSetOf()
        val pending = mockk<PendingBroadcastDao>()
        coEvery { pending.getActive(any(), any()) } returns emptyList()
        val realBuilder = TransactionBuilder(NetworkValidator())
        val builder = spyk(realBuilder)
        val inputs = slot<List<SweepInput>>()
        every { builder.buildSweep(capture(inputs), any(), any()) } answers { Result.success(invocation.originalCall() as SweepPlan) }
        val gateway = GapLimitGateway(database, mockk(), mockk(), mockk(), builder, ledger, pending,
            mockk(), json, NoopLogger)
        val context = GapLimitGateway.GapLimitContext({ NetworkType.TESTNET }, { "synthetic" }, { source },
            { SyncMode.FULL_HISTORY }, { null }, { null }, { error("No send in preview") }, { _, _, _ -> Result.success(Unit) })
        val preview = gateway.prepareGapLimitSweep(context).getOrThrow()
        assertEquals(300_00000001L, preview.totalShannons)
        assertEquals(1, preview.addressCount)
        val tx = realBuilder.buildSweep(inputs.captured, source, NetworkType.TESTNET).getOrThrow().transaction
        assertEquals(listOf(safe.outPoint), tx.cellInputs.map { it.previousOutput })
        assertEquals(299_99999001L, tx.cellOutputs.single().capacity.removePrefix("0x").toLong(16))
    }
}
