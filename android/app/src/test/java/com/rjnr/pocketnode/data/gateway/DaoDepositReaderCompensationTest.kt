package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.gateway.DaoUnlockShadowLightClientNative.MaxWithdrawCall
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.JniCell
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniTransactionView
import com.rjnr.pocketnode.data.gateway.models.JniTransactionWithStatus
import com.rjnr.pocketnode.data.gateway.models.JniTxStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #550: DAO compensation must treat the 102 CKB a deposit cell occupies as the
 * part that earns nothing. The reader passed 61 CKB (a plain secp256k1 cell),
 * which inflated every displayed amount by (C - 61) / (C - 102).
 *
 * Only the light-client reads are faked. This reuses
 * [DaoUnlockShadowLightClientNative] rather than declaring a second
 * `@Implements(LightClientNative::class)` shadow: LightClientNative is a Kotlin
 * `object`, so Robolectric binds its shadow once per sandbox and a second
 * shadow class fails with a ClassCastException in whichever suite runs second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [DaoUnlockShadowLightClientNative::class],
    instrumentedPackages = ["com.nervosnetwork.ckblightclient"],
)
class DaoDepositReaderCompensationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var reader: DaoDepositReader

    private val walletScript = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = "0x" + "11".repeat(20),
    )
    private val daoType = Script(codeHash = DaoConstants.DAO_CODE_HASH, hashType = "type", args = "0x")

    private val depositTx = "0x" + "aa".repeat(32)
    private val withdrawTx = "0x" + "bb".repeat(32)
    private val depositBlockHash = "0x" + "d1".repeat(32)
    private val withdrawBlockHash = "0x" + "d2".repeat(32)
    private val tipBlockHash = "0x" + "d3".repeat(32)

    private val depositDao = "0x0000000000000001"
    private val withdrawDao = "0x0000000000000002"
    private val tipDao = "0x0000000000000003"

    private val capacity = 1_000_00000000L // 1,000 CKB, the reporter's deposit

    private val headersByHash by lazy {
        mapOf(
            depositBlockHash to header(depositBlockHash, depositDao),
            withdrawBlockHash to header(withdrawBlockHash, withdrawDao),
            tipBlockHash to header(tipBlockHash, tipDao),
        )
    }

    private val calls get() = DaoUnlockShadowLightClientNative.maxWithdrawCalls

    @Before
    fun setup() {
        resetShadow()
        DaoUnlockShadowLightClientNative.tipHeader = json.encodeToString(header(tipBlockHash, tipDao))

        val resolver = mockk<DaoHeaderResolver>()
        // The deposited cell sits in the deposit block, the withdrawing cell in
        // the withdraw block; its original deposit header comes via header_deps.
        coEvery { resolver.getBlockHashForCell(depositTx) } returns depositBlockHash
        coEvery { resolver.getBlockHashForCell(withdrawTx) } returns withdrawBlockHash
        coEvery { resolver.getOrFetchHeader(any(), any()) } answers { headersByHash[firstArg()] }

        reader = DaoDepositReader(json, resolver, NoopLogger)
    }

    @After
    fun teardown() = resetShadow()

    private fun resetShadow() {
        DaoUnlockShadowLightClientNative.tipHeader = null
        DaoUnlockShadowLightClientNative.chainStatus = null
        DaoUnlockShadowLightClientNative.cellsPage = null
        DaoUnlockShadowLightClientNative.transactionOverride = null
        DaoUnlockShadowLightClientNative.maxWithdrawCalls.clear()
        DaoUnlockShadowLightClientNative.transactionQueries.clear()
    }

    @Test
    fun `deposited cell compensation uses the deposit cell occupied capacity`() = runTest {
        DaoUnlockShadowLightClientNative.cellsPage = cellsJson(depositedCell())

        val deposit = reader.list(walletScript, currentEpoch = null, network = NetworkType.TESTNET).single()

        assertEquals(listOf(MaxWithdrawCall(depositDao, tipDao, DaoConstants.DEPOSIT_OCCUPIED_SHANNONS)), calls)
        assertEquals(DaoUnlockShadowLightClientNative.FIXED_COMPENSATION, deposit.compensation)
    }

    @Test
    fun `withdrawing cell compensation uses the deposit cell occupied capacity`() = runTest {
        DaoUnlockShadowLightClientNative.cellsPage = cellsJson(withdrawingCell())
        DaoUnlockShadowLightClientNative.transactionOverride = json.encodeToString(phaseOneTx())

        val deposit = reader.list(walletScript, currentEpoch = null, network = NetworkType.TESTNET).single()

        assertEquals(listOf(MaxWithdrawCall(depositDao, withdrawDao, DaoConstants.DEPOSIT_OCCUPIED_SHANNONS)), calls)
        assertEquals(DaoUnlockShadowLightClientNative.FIXED_COMPENSATION, deposit.compensation)
    }

    @Test
    fun `cached withdrawing row is recomputed to its withdraw block from cached headers`() = runTest {
        val inflated = 22_530L
        val fresh = reader.recomputeCachedCompensation(
            cachedRow(withdrawBlockNumber = 200L, withdrawBlockHash = withdrawBlockHash, compensation = inflated),
        ) { hash -> headersByHash[hash]?.dao }

        assertEquals(listOf(MaxWithdrawCall(depositDao, withdrawDao, DaoConstants.DEPOSIT_OCCUPIED_SHANNONS)), calls)
        assertEquals(DaoUnlockShadowLightClientNative.FIXED_COMPENSATION, fresh)
    }

    @Test
    fun `cached deposited row is recomputed against the tip`() = runTest {
        val fresh = reader.recomputeCachedCompensation(cachedRow()) { hash -> headersByHash[hash]?.dao }

        assertEquals(listOf(MaxWithdrawCall(depositDao, tipDao, DaoConstants.DEPOSIT_OCCUPIED_SHANNONS)), calls)
        assertEquals(DaoUnlockShadowLightClientNative.FIXED_COMPENSATION, fresh)
    }

    @Test
    fun `cached row is left alone when a header is not cached`() = runTest {
        val row = cachedRow(withdrawBlockNumber = 200L, withdrawBlockHash = withdrawBlockHash)

        assertNull(reader.recomputeCachedCompensation(row) { hash -> if (hash == depositBlockHash) depositDao else null })
        assertNull(reader.recomputeCachedCompensation(row) { null })
        assertEquals(emptyList<MaxWithdrawCall>(), calls)
    }

    @Test
    fun `cached withdrawing row without a withdraw header is not measured to the tip`() = runTest {
        val row = cachedRow(withdrawBlockNumber = 200L, withdrawBlockHash = null)

        assertNull(reader.recomputeCachedCompensation(row) { hash -> headersByHash[hash]?.dao })
        assertEquals(emptyList<MaxWithdrawCall>(), calls)
    }

    private fun depositedCell() = daoCell(depositTx, data = "0x0000000000000000", blockNumber = "0x64")

    // Withdrawing cell data is the deposit block number, 8 bytes little-endian.
    private fun withdrawingCell() = daoCell(withdrawTx, data = "0x6400000000000000", blockNumber = "0xc8")

    private fun daoCell(txHash: String, data: String, blockNumber: String) = JniCell(
        output = CellOutput(capacity = "0x" + capacity.toString(16), lock = walletScript, type = daoType),
        outputData = data,
        outPoint = OutPoint(txHash = txHash, index = "0x0"),
        blockNumber = blockNumber,
        txIndex = "0x1",
    )

    private fun cellsJson(vararg cells: JniCell): String =
        json.encodeToString(JniPagination(objects = cells.toList(), lastCursor = null))

    private fun phaseOneTx() = JniTransactionWithStatus(
        transaction = JniTransactionView(
            hash = withdrawTx,
            version = "0x0",
            cellDeps = emptyList(),
            headerDeps = listOf(depositBlockHash),
            inputs = emptyList(),
            outputs = emptyList(),
            outputsData = emptyList(),
            witnesses = emptyList(),
        ),
        txStatus = JniTxStatus(status = "committed", blockHash = withdrawBlockHash),
    )

    private fun cachedRow(
        withdrawBlockNumber: Long? = null,
        withdrawBlockHash: String? = null,
        compensation: Long = 0L,
    ) = DaoCellEntity(
        txHash = depositTx,
        index = "0x0",
        capacity = capacity,
        status = if (withdrawBlockNumber != null) "UNLOCKABLE" else "DEPOSITED",
        depositBlockNumber = 100L,
        depositBlockHash = depositBlockHash,
        depositEpochHex = null,
        withdrawBlockNumber = withdrawBlockNumber,
        withdrawBlockHash = withdrawBlockHash,
        withdrawEpochHex = null,
        compensation = compensation,
        unlockEpochHex = null,
        depositTimestamp = 0L,
        network = NetworkType.TESTNET.name,
        lastUpdatedAt = 0L,
    )

    private fun header(hash: String, dao: String) = JniHeaderView(
        hash = hash,
        number = "0x64",
        epoch = "0x708000000000c8",
        timestamp = "0x18c8d0a7a00",
        parentHash = "0x" + "ef".repeat(32),
        transactionsRoot = "0x" + "e0".repeat(32),
        proposalsHash = "0x" + "e1".repeat(32),
        extraHash = "0x" + "e2".repeat(32),
        dao = dao,
        nonce = "0x0",
    )
}
