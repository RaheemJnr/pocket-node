package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.wallet.WalletInfo
import io.mockk.every
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

/** Real Room -> entity projection -> resolver -> reader -> gateway composition.
 * Only the native reads/arithmetic and unused send collaborators are doubled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [DaoUnlockShadowLightClientNative::class],
    instrumentedPackages = ["com.nervosnetwork.ckblightclient"])
class DaoDepositHeaderCacheIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val native get() = DaoUnlockShadowLightClientNative
    private lateinit var db: AppDatabase
    private lateinit var gate: VerificationCacheGate
    private lateinit var reader: DaoDepositReader
    private lateinit var resolver: DaoHeaderResolver
    private lateinit var gateway: DaoGateway
    private val network = NetworkType.TESTNET
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
    private val phaseHash = hash("aa")
    private val parentHash = hash("bb")
    private val deposit = header(hash("d1"), "0x64", "0x70800000000064", "0x10000000")
    private val withdraw = header(hash("d2"), "0xc8", "0x708000000000c8", "0x20000000")
    private val output = CellOutput("0x174876e800", script, DaoConstants.DAO_TYPE_SCRIPT)
    private val cell = JniCell(output, "0x6400000000000000", OutPoint(phaseHash, "0x0"), "0xc8", "0x1")

    @Before fun setup() = runTest {
        resetNative()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        gate = VerificationCacheGate(ApplicationProvider.getApplicationContext(), db)
        val api = mockk<LightClientApi>(relaxed = true)
        every { api.callRpc("get_verification_epoch") } returns
            """{"result":{"version":1,"generation":"${"e".repeat(32)}"}}"""
        every { api.getTipHeader() } returns json.encodeToString(withdraw)
        gate.reconcile(api, network)
        val sync = DaoSyncManager(db.headerCacheDao(), db.daoCellDao(), db.pendingDaoWithdrawDao(),
            db.pendingDaoUnlockDao(), NoopLogger, gate)
        resolver = DaoHeaderResolver(json, sync, NoopLogger)
        reader = DaoDepositReader(json, resolver, NoopLogger)
        gateway = DaoGateway(db, sync, reader, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate)
        listOf(deposit, withdraw).forEach {
            native.headersByHash[it.hash] = json.encodeToString(it)
            native.headersByNumber[it.number] = json.encodeToString(it)
        }
        val phase = JniTransactionView(phaseHash, "0x0", emptyList(), listOf(deposit.hash),
            listOf(CellInput(previousOutput = OutPoint(parentHash, "0x0"))),
            listOf(output), listOf(cell.outputData!!), emptyList())
        native.transactionsByHash[phaseHash] = json.encodeToString(JniTransactionWithStatus(phase,
            txStatus = JniTxStatus("committed", withdraw.hash)))
        // A missing old parent is intentionally permitted by the existing contract.
        native.cellsPage = json.encodeToString(JniPagination(listOf(cell), null))
    }

    @After fun teardown() { db.close(); resetNative() }
    private fun resetNative() {
        native.headersByHash.clear(); native.headersByNumber.clear(); native.transactionsByHash.clear()
        native.tipHeader = null; native.chainStatus = null; native.cellsPage = null; native.transactionOverride = null
        native.maxWithdrawCalls.clear(); native.transactionQueries.clear()
    }

    @Test fun `withdrawal header cached projection preserves single deposit resolution`() = runTest {
        cache(withdraw)
        resolved(reader.list(script, null, network).single())
    }

    @Test fun `deposit header cached projection preserves single deposit resolution`() = runTest {
        cache(deposit)
        resolved(reader.list(script, null, network).single())
    }

    @Test fun `both cached projections preserve single deposit resolution`() = runTest {
        cache(deposit); cache(withdraw)
        resolved(reader.list(script, null, network).single())
    }

    @Test fun `two successive gateway reads stay resolved after reduced headers are committed`() = runTest {
        resolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
        val cached = db.headerCacheDao().getByBlockHash(withdraw.hash)!!
        assertEquals("", cached.toJniHeaderView().parentHash)
        resolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
        val stored = db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!
        assertEquals(deposit.hash, stored.depositBlockHash)
        assertNotNull(stored.unlockEpochHex)
        assertEquals(1_000L, stored.compensation)
    }

    @Test fun `conflicting persisted header fields stay unresolved for either cache role`() = runTest {
        for (header in listOf(deposit, withdraw)) {
            val original = HeaderCacheEntity.from(header, network.name)
            val conflicts = listOf(original.copy(number = "0x65"), original.copy(epoch = "0x70800000000065"),
                original.copy(timestamp = "0x30000000"), original.copy(dao = hash("ff")))
            for (bad in conflicts) {
                db.headerCacheDao().deleteAll()
                db.headerCacheDao().upsert(bad)
                val position = reader.list(script, null, network).single()
                assertEquals("${header.number}: $bad", "", position.depositBlockHash)
                assertNull(position.unlockEpoch)
                assertEquals(0L, position.compensation)
            }
        }
    }

    @Test fun `same height cached fork cannot replace native canonical deposit identity`() = runTest {
        val fork = deposit.copy(hash = hash("ff"))
        cache(fork)
        val phase = json.decodeFromString<JniTransactionWithStatus>(native.transactionsByHash.getValue(phaseHash))
        native.transactionsByHash[phaseHash] = json.encodeToString(phase.copy(
            transaction = phase.transaction!!.copy(headerDeps = listOf(fork.hash))))
        val position = reader.list(script, null, network).single()
        assertEquals("", position.depositBlockHash)
        assertNull(position.unlockEpoch)
        assertEquals(0L, position.compensation)
    }

    private suspend fun cache(header: JniHeaderView) {
        db.headerCacheDao().upsert(HeaderCacheEntity.from(header, network.name))
    }
    private fun resolved(position: DaoDeposit) {
        assertEquals(deposit.hash, position.depositBlockHash)
        assertEquals(EpochInfo(100, 0, 28800), position.depositEpoch)
        assertEquals(0x10000000L, position.depositTimestamp)
        assertEquals(withdraw.hash, position.withdrawBlockHash)
        assertEquals(EpochInfo(200, 0, 28800), position.withdrawEpoch)
        assertEquals(1_000L, position.compensation)
        assertNotNull(position.unlockEpoch)
    }
    private fun ctx() = DaoGateway.DaoContext(network = { network }, walletId = { "wallet" },
        walletInfo = { WalletInfo("pub", script, "ckt1", "ckb1") }, currentAddress = { "ckt1" },
        existingScriptBlock = { 1000L }, privateKey = { error("No key access") },
        sendContext = { error("No sends") }, transactionStatus = { Result.failure(Exception("unused")) },
        setScriptsAndRecord = { _, _, _, _ -> error("No registration") })
    private fun hash(byte: String) = "0x" + byte.repeat(32)
    private fun header(hash: String, number: String, epoch: String, timestamp: String) = JniHeaderView(
        hash, number, epoch, timestamp, hash("e0"), hash("e1"), hash("e2"), hash("e3"), hash("e4"), "0x1")
}
