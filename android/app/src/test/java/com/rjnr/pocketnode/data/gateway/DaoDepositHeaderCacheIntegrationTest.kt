package com.rjnr.pocketnode.data.gateway

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.database.entity.PendingDaoUnlockEntity
import com.rjnr.pocketnode.data.database.entity.PendingDaoWithdrawEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.wallet.WalletInfo
import io.mockk.coEvery
import io.mockk.spyk
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
    private lateinit var recovery: DaoRecoveryCoordinator
    private lateinit var reader: DaoDepositReader
    private lateinit var resolver: DaoHeaderResolver
    private lateinit var gateway: DaoGateway
    private lateinit var sync: DaoSyncManager
    private lateinit var api: LightClientApi
    private val network = NetworkType.TESTNET
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
    private val phaseHash = hash("aa")
    private val parentHash = hash("bb")
    private val deposit = header(hash("d1"), "0x64", "0x70800000000064", "0x10000000")
    private val withdraw = header(hash("d2"), "0xc8", "0x708000000000c8", "0x20000000").copy(dao = hash("e2"))
    private val output = CellOutput("0x174876e800", script, DaoConstants.DAO_TYPE_SCRIPT)
    private val cell = JniCell(output, "0x6400000000000000", OutPoint(phaseHash, "0x0"), "0xc8", "0x1")

    @Before fun setup() = runTest {
        resetNative()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        gate = VerificationCacheGate(ApplicationProvider.getApplicationContext(), db)
        api = mockk<LightClientApi>(relaxed = true)
        every { api.callRpc("get_verification_epoch") } returns
            """{"result":{"version":1,"generation":"${"e".repeat(32)}"}}"""
        every { api.getTipHeader() } returns json.encodeToString(withdraw)
        gate.reconcile(api, network)
        sync = DaoSyncManager(db.headerCacheDao(), db.daoCellDao(), db.pendingDaoWithdrawDao(),
            db.pendingDaoUnlockDao(), NoopLogger, gate)
        recovery = DaoRecoveryCoordinator(db, gate, api, json, DaoRecoveryLifetime().apply { onStarted() })
        resolver = DaoHeaderResolver(json, sync, NoopLogger)
        reader = DaoDepositReader(json, resolver, NoopLogger, recovery)
        gateway = DaoGateway(db, sync, reader, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate, recovery)
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

    @Test fun `gate invalidation during final suspended projection prevents publication`() = runTest {
        val finalProjection = spyk(recovery)
        coEvery { finalProjection.project(any(), any(), any()) } coAnswers {
            val projected = recovery.project(firstArg(), secondArg(), thirdArg())
            // Staged reads happen before insertion; only the final projection sees it.
            if (db.daoCellDao().allRows().isNotEmpty()) gate.close()
            projected
        }
        val finalGateway = DaoGateway(db, sync, reader, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate, finalProjection)
        assertTrue(finalGateway.getDaoDeposits(ctx()).isFailure)
        assertTrue(db.daoCellDao().allRows().isNotEmpty())
    }

    @Test fun `adapter invalidation while final second position suspends rejects earlier actionable position`() = runTest {
        gateway.getDaoDeposits(ctx()).getOrThrow()
        adoptLocator()
        val secondCell = cell.copy(outPoint = OutPoint(hash("a2"), "0x0"), outputData = "0x0000000000000000", blockNumber = "0x64")
        native.cellsPage = json.encodeToString(JniPagination(listOf(cell, secondCell), null))
        val projectedA = kotlinx.coroutines.CompletableDeferred<DaoDeposit>()
        val releaseB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val parkedB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finalProjection = spyk(recovery)
        every { finalProjection.invalidation } returns recovery.invalidation
        var committed = false
        val publicationGate = spyk(gate)
        coEvery { publicationGate.commitIfCurrent(any(), any(), any()) } coAnswers {
            gate.commitIfCurrent(firstArg(), secondArg(), thirdArg())
            committed = true
        }
        coEvery { finalProjection.project(any(), any(), any()) } coAnswers {
            val position = thirdArg<DaoDeposit>()
            if (position.outPoint == secondCell.outPoint && committed) { parkedB.complete(Unit); releaseB.await() }
            val projected = recovery.project(firstArg(), secondArg(), position)
            if (position.outPoint == cell.outPoint && committed) projectedA.complete(projected)
            projected
        }
        val finalGateway = DaoGateway(db, sync, reader, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, publicationGate, finalProjection)
        val revision = gate.currentRevision()
        val pending = async { finalGateway.getDaoDeposits(ctx()) }
        assertNotNull(projectedA.await().recoveryReceiptId)
        parkedB.await()
        recoveryValid = false
        recovery.refreshNativeValidity()
        assertTrue(recovery.invalidation.value > 0)
        assertEquals(recovery.invalidation.value, finalProjection.invalidation.value)
        assertTrue(gate.isReady(network.name))
        assertEquals(revision, gate.currentRevision())
        releaseB.complete(Unit)
        assertTrue("old multi-position publication must be rejected", pending.await().isFailure)
    }

    @Test fun `persisted recovery obligation blocks retained matching mappings after restart`() = runTest {
        db.daoRecoveryDao().putGuard(com.rjnr.pocketnode.data.database.entity.DaoRecoveryEntity(
            "wallet", network.name, hash("01"), phaseHash, 0L, 100_000_000_000L, 100L,
            json.encodeToString(script), 200L, withdraw.hash, "old-store", "old-run", 1))
        repeat(2) {
            val result = gateway.getDaoDeposits(ctx()).getOrThrow().single()
            assertNull("matching retained native mappings cannot authorize a guarded position", result.unlockEpoch)
            assertEquals(0L, result.compensation)
        }
        db.daoCellDao().deleteAll()
        assertNull(gateway.getDaoDeposits(ctx()).getOrThrow().single().unlockEpoch)
    }

    private var recoveryValid = true
    private var proofSequence = 1
    private var proofReceipt: DaoRecoveryReceipt? = null
    private val recoveredHeader get() = withdraw.copy(hash = hash("f1"), number = "0x12c", epoch = "0x7080000000012c")

    private suspend fun adoptLocator(): DaoRecoveryContext {
        if (db.walletDao().getActive() == null) db.walletDao().insert(
            com.rjnr.pocketnode.data.database.entity.WalletEntity("wallet", "Synthetic", "raw_key", null, null, isActive = true))
        val identity = DaoRecoveryIdentity(hash("01"), "e".repeat(32), "0x" + "02".repeat(16))
        val h = recoveredHeader
        val header = DaoRecoveryHeader("0x0", "0x0", h.timestamp, h.number, h.epoch, h.parentHash,
            h.transactionsRoot, h.proposalsHash, h.extraHash, h.dao, h.nonce, h.hash)
        val token = proofSequence.toString(16).padStart(2, '0')
        val request = "dr1.q.$token"
        val receipt = DaoRecoveryReceipt(1, "raw_transaction_membership", "dr1.r.$token", request, "dr1.a.$token",
            identity, "1", header, phaseHash, DaoRecoveryRawTransaction("0x0", emptyList(), listOf(deposit.hash),
                listOf(DaoRecoveryInput("0x0", OutPoint(parentHash, "0x0"))),
                listOf(DaoRecoveryOutput(output.capacity, output.lock, output.type)), listOf(cell.outputData!!)), header, null)
        proofReceipt = receipt
        every { api.requestDaoRecovery(any()) } returns
            """{"schema_version":1,"operation":"request","result":{"request_id":"$request","transaction_hash":"$phaseHash","identity":${json.encodeToString(identity)}}}"""
        every { api.pollDaoRecovery(any()) } answers {
            """{"schema_version":1,"operation":"poll","result":{"request_id":"$request","status":"verified","receipt":${json.encodeToString(receipt)}}}"""
        }
        every { api.validateDaoRecovery(any(), any()) } answers {
            if (recoveryValid && secondArg<String>() == receipt.receiptId)
                """{"schema_version":1,"operation":"validate","result":{"request_id":"$request","valid":true,"receipt":${json.encodeToString(receipt)}}}"""
            else """{"schema_version":1,"operation":"validate","error":{"code":"invalid_receipt"}}"""
        }
        val context = recovery.begin("wallet", network, script, hash("01"), cell.outPoint)
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        // The native association/header store moved, but its script index remains old.
        native.headersByHash[h.hash] = json.encodeToString(h)
        native.headersByNumber[h.number] = json.encodeToString(h)
        return context
    }

    @Test fun `validated receipt replaces stale live script index locator without trusting retained native phase association`() = runTest {
        gateway.getDaoDeposits(ctx()).getOrThrow()
        adoptLocator()
        val position = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        assertEquals(300L, position.withdrawBlockNumber)
        assertEquals(recoveredHeader.hash, position.withdrawBlockHash)
        assertNotNull(position.unlockEpoch)
        assertEquals("dr1.r.01", position.recoveryReceiptId)
        assertEquals("0xc8", json.decodeFromString<JniPagination<JniCell>>(native.cellsPage!!).objects.single().blockNumber)
        assertEquals(withdraw.hash, json.decodeFromString<JniTransactionWithStatus>(native.transactionsByHash.getValue(phaseHash)).txStatus.blockHash)
    }

    @Test fun `native invalidation after reader preparation suppresses late projection and repeated retained mapping refreshes`() = runTest {
        gateway.getDaoDeposits(ctx()).getOrThrow()
        adoptLocator()
        val realReader = reader
        val staged = spyk(reader)
        coEvery { staged.list(any(), any(), any(), any()) } coAnswers {
            val result = realReader.list(firstArg(), secondArg(), thirdArg(), arg(3))
            recoveryValid = false
            result
        }
        gateway = DaoGateway(db, sync, staged, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate, recovery)
        repeat(2) { unresolved(gateway.getDaoDeposits(ctx()).getOrThrow().single()) }
        assertEquals(1, recovery.guards(network, cell.outPoint).size)
        assertTrue(recovery.invalidation.value > 0)
    }

    @Test fun `adoption fences a real refresh staged before native proof and SQL commit`() = runTest {
        gateway.getDaoDeposits(ctx()).getOrThrow()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val realReader = reader
        val staged = spyk(reader)
        coEvery { staged.list(any(), any(), any(), any()) } coAnswers {
            val result = realReader.list(firstArg(), secondArg(), thirdArg(), arg(3))
            entered.complete(Unit); resume.await(); result
        }
        val oldGateway = DaoGateway(db, sync, staged, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate, recovery)
        val job = async { oldGateway.getDaoDeposits(ctx()) }
        entered.await()
        adoptLocator()
        resume.complete(Unit)
        assertTrue(job.await().isFailure)
        assertEquals(300L, db.daoCellDao().allRows().single().withdrawBlockNumber)
    }

    @Test fun `completed recovered alias stays terminal and outside window proof does not make position live`() = runTest {
        gateway.getDaoDeposits(ctx()).getOrThrow()
        adoptLocator()
        native.cellsPage = null
        val outside = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        assertTrue(outside.outsideSyncWindow)
        assertNotNull(outside.recoveryReceiptId)
        val row = db.daoCellDao().allRows().single()
        db.daoCellDao().upsert(row.copy(index = "00", status = "COMPLETED"))
        native.cellsPage = json.encodeToString(JniPagination(listOf(cell), null))
        assertEquals(DaoCellStatus.COMPLETED, gateway.getDaoDeposits(ctx()).getOrThrow().single().status)
    }

    @Test fun `compensation invalidated while reading cached headers is not published`() = runTest {
        gateway.getDaoDeposits(ctx()).getOrThrow()
        adoptLocator()
        gateway.getDaoDeposits(ctx()).getOrThrow()
        val stored = db.daoCellDao().allRows().single()
        val result = reader.recomputeCachedCompensation(stored) { hash ->
            recoveryValid = false
            if (hash == deposit.hash) deposit.dao else recoveredHeader.dao
        }
        assertNull(result)
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

    @Test fun `cached null withdrawal proof rehydrates in reader and durable gateway row`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawBlockHash = null, withdrawEpochHex = null)
        db.daoCellDao().upsert(original)
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        val checked = reader.revalidateCachedWithdrawing(original, null, network)
        assertEquals(withdraw.hash, checked.withdrawBlockHash)
        assertEquals(withdraw.epoch, checked.withdrawEpochHex)
        val result = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        resolved(result)
        assertTrue(result.outsideSyncWindow)
        val stored = db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!
        assertEquals(withdraw.hash, stored.withdrawBlockHash)
        assertEquals(withdraw.epoch, stored.withdrawEpochHex)
        assertEquals(original.withdrawBlockNumber, stored.withdrawBlockNumber)
    }

    @Test fun `same withdrawal identity corrects stale epoch using canonical header`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawEpochHex = deposit.epoch)
        db.daoCellDao().upsert(original)
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        resolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
        assertEquals(withdraw.epoch, db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!.withdrawEpochHex)
    }

    @Test fun `withdrawal contradiction survives repeated cached and live refreshes`() = runTest {
        val oldHash = hash("cf")
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawBlockHash = oldHash)
        db.daoCellDao().upsert(original)
        cache(withdraw.copy(hash = oldHash, dao = hash("ef")))
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        repeat(3) {
            val result = gateway.getDaoDeposits(ctx()).getOrThrow().single()
            unresolved(result)
            assertTrue(result.outsideSyncWindow)
            val stored = db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!
            assertEquals(oldHash, stored.withdrawBlockHash) // historical locator, never exposed as proof
            assertNull(stored.withdrawEpochHex)
            assertEquals(original.withdrawBlockNumber, stored.withdrawBlockNumber)
        }
        native.cellsPage = json.encodeToString(JniPagination(listOf(cell), null))
        repeat(2) {
            unresolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
            assertEquals(oldHash, db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!.withdrawBlockHash)
        }
        assertFalse(native.maxWithdrawCalls.any { it.endDao == hash("ef") })
    }

    @Test fun `missing phase proof masks historical locator and remains retryable`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        db.daoCellDao().upsert(original)
        val phase = native.transactionsByHash.remove(phaseHash)!!
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        unresolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
        val stored = db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!
        assertEquals(withdraw.hash, stored.withdrawBlockHash)
        assertNull(stored.withdrawEpochHex)
        native.transactionsByHash[phaseHash] = phase
        resolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
    }

    @Test fun `missing canonical withdrawal evidence clears claims and later rehydrates`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        db.daoCellDao().upsert(original)
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        native.headersByNumber.remove(withdraw.number)
        native.maxWithdrawCalls.clear()
        unresolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
        assertTrue(native.maxWithdrawCalls.isEmpty())
        native.headersByNumber[withdraw.number] = json.encodeToString(withdraw)
        resolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
        assertTrue(native.maxWithdrawCalls.all { it.endDao == withdraw.dao })
    }

    @Test fun `unresolved pending unlock preserves marker and position without proof`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        db.daoCellDao().upsert(original)
        db.pendingDaoUnlockDao().upsert(PendingDaoUnlockEntity(phaseHash, "0x0", hash("ab"),
            "wallet", network.name, System.currentTimeMillis()))
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        native.transactionsByHash.remove(phaseHash)
        val shown = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        assertEquals(DaoCellStatus.UNLOCKING, shown.status)
        assertEquals(original.txHash, shown.outPoint.txHash)
        assertFalse(shown.outsideSyncWindow)
        assertNull(shown.withdrawBlockHash)
        assertNull(shown.unlockEpoch)
        assertEquals(0L, shown.compensation)
        assertEquals(1, db.pendingDaoUnlockDao().getByWalletAndNetwork("wallet", network.name).size)
    }

    @Test fun `current-row proof copy preserves concurrent status and position`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawBlockHash = null, withdrawEpochHex = null)
        db.daoCellDao().upsert(original)
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        duringRevalidation {
            db.daoCellDao().upsert(original.copy(status = DaoCellStatus.UNLOCKING.name, lastUpdatedAt = 99))
        }
        gateway.getDaoDeposits(ctx()).getOrThrow()
        val stored = db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!
        assertEquals(DaoCellStatus.UNLOCKING.name, stored.status)
        assertEquals(99L, stored.lastUpdatedAt)
        assertEquals(original.withdrawBlockNumber, stored.withdrawBlockNumber)
        assertEquals(withdraw.hash, stored.withdrawBlockHash)
        assertEquals(withdraw.epoch, stored.withdrawEpochHex)
    }

    @Test fun `proof refresh cannot modify a row completed during evidence reads`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawBlockHash = null, withdrawEpochHex = null)
        db.daoCellDao().upsert(original)
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        val retired = original.copy(status = DaoCellStatus.COMPLETED.name, lastUpdatedAt = 99)
        duringRevalidation { db.daoCellDao().upsert(retired) }
        gateway.getDaoDeposits(ctx()).getOrThrow()
        assertEquals(retired, db.daoCellDao().getByOutPoint(phaseHash, "0x0"))
    }

    @Test fun `actual database failure cannot authorize a live exact-key position`() = runTest {
        assertDatabaseFailureDoesNotAuthorize("0x0")
    }

    @Test fun `actual database failure cannot insert an actionable alternate index`() = runTest {
        assertDatabaseFailureDoesNotAuthorize("0x00")
    }

    private suspend fun assertDatabaseFailureDoesNotAuthorize(liveIndex: String) {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawBlockHash = hash("cf"))
        db.daoCellDao().upsert(original)
        val headers = db.headerCacheDao().getByNetwork(network.name)
        val marker = PendingDaoUnlockEntity(phaseHash, "0x0", hash("ab"), "wallet", network.name,
            System.currentTimeMillis())
        db.pendingDaoUnlockDao().upsert(marker)
        native.cellsPage = json.encodeToString(JniPagination(listOf(cell.copy(
            outPoint = cell.outPoint.copy(index = liveIndex))), null))
        var failOnce = true
        withHistoryReadHook {
            if (failOnce) {
                failOnce = false
                throw SQLiteException("task-owned association read failure")
            }
        }
        val result = gateway.getDaoDeposits(ctx())
        assertNull("No alternate spelling may gain a durable association", db.daoCellDao().getByOutPoint(phaseHash, "0x00"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SQLiteException)
        assertEquals(listOf(original), db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name))
        assertEquals(headers, db.headerCacheDao().getByNetwork(network.name))
        assertEquals(listOf(marker), db.pendingDaoUnlockDao().getByWalletAndNetwork("wallet", network.name))
        assertNull(db.daoCellDao().getByOutPoint(phaseHash, "0x00"))
        // A successful retry still sees the historical contradiction and marker.
        val retry = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        assertEquals(DaoCellStatus.UNLOCKING, retry.status)
        assertNull(retry.withdrawBlockHash)
        assertNull(retry.unlockEpoch)
        assertEquals(0L, retry.compensation)
        assertEquals(hash("cf"), db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!.withdrawBlockHash)
        assertNull(db.daoCellDao().getByOutPoint(phaseHash, "0x00"))
        assertEquals(listOf(marker), db.pendingDaoUnlockDao().getByWalletAndNetwork("wallet", network.name))
    }

    @Test fun `actual history read cancellation cannot publish or stage a position`() = runTest {
        withHistoryReadHook { throw CancellationException("cancel history read") }
        val failure = runCatching { gateway.getDaoDeposits(ctx()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name).isEmpty())
        assertTrue(db.headerCacheDao().getByNetwork(network.name).isEmpty())
    }

    @Test fun `history read readiness loss cannot become successful empty history`() = runTest {
        withHistoryReadHook { gate.close() }
        assertTrue(gateway.getDaoDeposits(ctx()).isFailure)
        assertTrue(db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name).isEmpty())
        assertTrue(db.headerCacheDao().getByNetwork(network.name).isEmpty())
    }

    @Test fun `history read close and reopen invalidates the original revision`() = runTest {
        withHistoryReadHook { gate.reconcile(api, network) }
        assertTrue(gateway.getDaoDeposits(ctx()).isFailure)
        assertTrue(gate.isReady(network.name))
        assertTrue(db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name).isEmpty())
        assertTrue(db.headerCacheDao().getByNetwork(network.name).isEmpty())
    }

    @Test fun `not-ready refresh fails while tolerant history reads retain empty UI semantics`() = runTest {
        var reads = 0
        withHistoryReadHook { reads++ }
        gate.close()
        assertTrue(gateway.getDaoDeposits(ctx()).isFailure)
        assertTrue(sync.getActiveDeposits(network.name, "wallet").isEmpty())
        assertTrue(runCatching { sync.requireActiveDepositHistory(network.name, "wallet") }.isFailure)
        assertEquals(0, reads)
    }

    @Test fun `strict history API rejects a changed revision even if readiness returns`() = runTest {
        withHistoryReadHook { gate.reconcile(api, network) }
        val result = runCatching { sync.requireActiveDepositHistory(network.name, "wallet") }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(gate.isReady(network.name))
    }

    @Test fun `deposited live aliases retain one durable stored key across refreshes`() = runTest {
        assertDepositedAliasIdentity()
    }

    @Test fun `deposited alias restores the prior key when row disappears after history read`() = runTest {
        assertDepositedAliasIdentity(removeAfterRead = true)
    }

    @Test fun `deposited alias preserves concurrent status on the existing raw key`() = runTest {
        assertDepositedAliasIdentity(changeStatusAfterRead = true)
    }

    private suspend fun assertDepositedAliasIdentity(
        removeAfterRead: Boolean = false,
        changeStatusAfterRead: Boolean = false,
    ) {
        for ((storedIndex, liveIndex) in listOf("0x0" to "0x00", "0x00" to "0x0")) {
            db.daoCellDao().deleteAll()
            native.cellsPage = json.encodeToString(JniPagination(listOf(
                cell.copy(outputData = "0x0000000000000000",
                    outPoint = cell.outPoint.copy(index = liveIndex))), null))
            val fresh = reader.list(script, null, network).single()
                .toDaoCellEntity(network.name, "wallet", 7)
            assertNull(fresh.withdrawBlockNumber)
            val stored = fresh.copy(index = storedIndex, lastUpdatedAt = 1)
            db.daoCellDao().upsert(stored)
            var changed = false
            withHistoryReadHook(afterRead = {
                if (!changed) {
                    changed = true
                    if (removeAfterRead) db.daoCellDao().deleteAll()
                    if (changeStatusAfterRead) db.daoCellDao().updateStatus(
                        stored.txHash, stored.index, DaoCellStatus.UNLOCKING.name)
                }
            }) {}
            repeat(if (changeStatusAfterRead) 1 else 2) {
                val positions = gateway.getDaoDeposits(ctx()).getOrThrow()
                assertEquals(1, positions.size)
                val rows = db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name)
                assertEquals("$storedIndex -> $liveIndex must not create a second raw key", 1, rows.size)
                val retained = rows.single()
                assertEquals(stored.txHash, retained.txHash)
                assertEquals(storedIndex, retained.index)
                assertNull(db.daoCellDao().getByOutPoint(stored.txHash, liveIndex))
                assertEquals(if (changeStatusAfterRead) DaoCellStatus.UNLOCKING.name else fresh.status,
                    retained.status)
                assertTrue(retained.lastUpdatedAt > stored.lastUpdatedAt)
                assertEquals(fresh.depositBlockHash, retained.depositBlockHash)
            }
        }
    }

    private fun withHistoryReadHook(
        afterRead: suspend () -> Unit = {},
        beforeRead: suspend () -> Unit,
    ) {
        val roomDao = db.daoCellDao()
        val faultingDao = object : DaoCellDao by roomDao {
            override suspend fun getActiveByWalletAndNetwork(walletId: String, network: String):
                List<com.rjnr.pocketnode.data.database.entity.DaoCellEntity> {
                beforeRead()
                val rows = roomDao.getActiveByWalletAndNetwork(walletId, network)
                afterRead()
                return rows
            }
        }
        // Real manager: the injected failure occurs beneath its exception policy.
        sync = DaoSyncManager(db.headerCacheDao(), faultingDao, db.pendingDaoWithdrawDao(),
            db.pendingDaoUnlockDao(), NoopLogger, gate)
        gateway = DaoGateway(db, sync, reader, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate, recovery)
    }

    @Test fun `normalized duplicate cannot hide a historical withdrawal contradiction`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        db.daoCellDao().upsert(original.copy(withdrawBlockHash = hash("cf")))
        db.daoCellDao().upsert(original.copy(index = "0x00"))
        repeat(2) {
            unresolved(gateway.getDaoDeposits(ctx()).getOrThrow().single())
            assertEquals(hash("cf"), db.daoCellDao().getByOutPoint(phaseHash, "0x0")!!.withdrawBlockHash)
        }
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        repeat(2) {
            val positions = gateway.getDaoDeposits(ctx()).getOrThrow()
            assertEquals(1, positions.size)
            assertEquals(original.capacity, positions.sumOf { it.capacity })
            positions.forEach(::unresolved)
        }
    }

    @Test fun `cached null aliases honor conflicting history on every repeated refresh`() = runTest {
        assertNullAliasHistory(live = false, matches = false)
    }

    @Test fun `live null aliases honor conflicting history regardless of row order`() = runTest {
        assertNullAliasHistory(live = true, matches = false)
    }

    @Test fun `cached null aliases recover when fresh proof matches all history`() = runTest {
        assertNullAliasHistory(live = false, matches = true)
    }

    @Test fun `live null aliases recover when fresh proof matches all history`() = runTest {
        assertNullAliasHistory(live = true, matches = true)
    }

    @Test fun `cached aliases display one capacity while both rows refresh canonical metadata`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
            .copy(withdrawEpochHex = deposit.epoch)
        db.daoCellDao().upsert(original.copy(index = "0x00"))
        db.daoCellDao().upsert(original)
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        repeat(3) {
            val shown = gateway.getDaoDeposits(ctx()).getOrThrow()
            assertEquals(1, shown.size)
            assertEquals(original.capacity, shown.sumOf { it.capacity })
            resolved(shown.single())
            assertEquals("0x0", shown.single().outPoint.index)
            assertTrue(shown.single().outsideSyncWindow)
            val stored = db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name)
            assertEquals(2, stored.size)
            assertEquals(setOf("0x0", "0x00"), stored.map { it.index }.toSet())
            stored.forEach {
                assertEquals(withdraw.hash, it.withdrawBlockHash)
                assertEquals(withdraw.epoch, it.withdrawEpochHex)
                assertEquals(original.withdrawBlockNumber, it.withdrawBlockNumber)
            }
        }
    }

    @Test fun `cached display prefers unresolved evidence over an actionable alias`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        db.daoCellDao().upsert(original)
        // This alias no longer matches the native output, so its proof must clear.
        db.daoCellDao().upsert(original.copy(index = "0x00", capacity = original.capacity + 1))
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        val shown = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        unresolved(shown)
        assertEquals("0x00", shown.outPoint.index)
        assertEquals(2, db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name).size)
    }

    @Test fun `cached display preserves a pending alias over a resolved actionable alias`() = runTest {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        db.daoCellDao().upsert(original)
        db.daoCellDao().upsert(original.copy(index = "0x00", status = DaoCellStatus.UNLOCKING.name))
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        val shown = gateway.getDaoDeposits(ctx()).getOrThrow().single()
        assertEquals(DaoCellStatus.UNLOCKING, shown.status)
        assertEquals("0x00", shown.outPoint.index)
        assertEquals(original.capacity, shown.capacity)
        assertEquals(2, db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name).size)
    }

    @Test fun `pending phase one marker overlays the single normalized deposited position`() = runTest {
        assertPhaseOneAliasMarker("PENDING")
    }

    @Test fun `unknown phase one marker overlays and survives normalized alias display`() = runTest {
        assertPhaseOneAliasMarker("UNKNOWN")
    }

    @Test fun `verified committed phase one marker is retained while normalized deposit remains`() = runTest {
        assertPhaseOneAliasMarker("CONFIRMED")
    }

    @Test fun `failed phase one marker is deleted by its exact stored alias key`() = runTest {
        assertPhaseOneAliasMarker("FAILED")
    }

    private suspend fun assertPhaseOneAliasMarker(txStatus: String) {
        native.cellsPage = json.encodeToString(JniPagination(listOf(cell.copy(outputData = "0x0000000000000000")), null))
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        val broadcastHash = hash("bc")
        native.cellsPage = json.encodeToString(JniPagination(emptyList<JniCell>(), null))
        for (markerIndex in listOf("0x0", "0x00")) {
            for (reverseOrder in listOf(false, true)) {
                db.daoCellDao().deleteAll()
                db.pendingDaoWithdrawDao().deleteByWalletAndNetwork("wallet", network.name)
                val rows = listOf(original, original.copy(index = "0x00"))
                (if (reverseOrder) rows.reversed() else rows).forEach { db.daoCellDao().upsert(it) }
                val marker = PendingDaoWithdrawEntity(phaseHash, markerIndex, broadcastHash, "wallet", network.name, 7)
                db.pendingDaoWithdrawDao().upsert(marker)
                if (txStatus != "UNKNOWN") db.transactionDao().insert(TransactionEntity(
                    txHash = broadcastHash, blockNumber = if (txStatus == "CONFIRMED") "0xd0" else "0x0",
                    blockHash = if (txStatus == "CONFIRMED") hash("bd") else "", timestamp = 7,
                    balanceChange = "0x0", direction = "dao_withdraw", fee = "0x0",
                    confirmations = if (txStatus == "CONFIRMED") 1 else 0, blockTimestampHex = null,
                    network = network.name, status = txStatus, isLocal = true, cachedAt = 7, walletId = "wallet"))
                repeat(2) {
                    val shown = gateway.getDaoDeposits(ctx()).getOrThrow()
                    assertEquals(1, shown.size)
                    assertEquals(original.capacity, shown.sumOf { it.capacity })
                    assertEquals("0x0", shown.single().outPoint.index)
                    assertEquals(if (txStatus == "FAILED") DaoCellStatus.DEPOSITED else DaoCellStatus.WITHDRAWING,
                        shown.single().status)
                    val remaining = db.pendingDaoWithdrawDao().getByWalletAndNetwork("wallet", network.name)
                    assertEquals(if (txStatus == "FAILED") emptyList<PendingDaoWithdrawEntity>() else listOf(marker), remaining)
                    assertEquals(setOf("0x0", "0x00"), db.daoCellDao().getActiveByWalletAndNetwork("wallet", network.name)
                        .map { it.index }.toSet())
                }
            }
        }
    }

    private suspend fun assertNullAliasHistory(live: Boolean, matches: Boolean) {
        val original = reader.list(script, null, network).single().toDaoCellEntity(network.name, "wallet", 7)
        val oldHash = if (matches) withdraw.hash else hash("cf")
        for (nullIndex in listOf("0x0", "0x00")) {
            for (nullFirst in listOf(true, false)) {
                db.daoCellDao().deleteAll()
                val nullRow = original.copy(index = nullIndex, withdrawBlockHash = null, withdrawEpochHex = null)
                val historicRow = original.copy(index = if (nullIndex == "0x0") "0x00" else "0x0",
                    withdrawBlockHash = oldHash)
                val ordered = if (nullFirst) listOf(nullRow, historicRow) else listOf(historicRow, nullRow)
                ordered.forEach { db.daoCellDao().upsert(it) }
                native.cellsPage = json.encodeToString(JniPagination(if (live) listOf(cell) else emptyList(), null))
                repeat(3) {
                    val shown = gateway.getDaoDeposits(ctx()).getOrThrow()
                    assertEquals(1, shown.size)
                    assertEquals(original.capacity, shown.sumOf { it.capacity })
                    shown.forEach { position ->
                        if (matches) resolved(position) else unresolved(position)
                        assertEquals(!live, position.outsideSyncWindow)
                    }
                    val storedNull = db.daoCellDao().getByOutPoint(phaseHash, nullRow.index)!!
                    val storedHistoric = db.daoCellDao().getByOutPoint(phaseHash, historicRow.index)!!
                    assertEquals(oldHash, storedHistoric.withdrawBlockHash)
                    assertEquals(original.withdrawBlockNumber, storedNull.withdrawBlockNumber)
                    assertEquals(original.withdrawBlockNumber, storedHistoric.withdrawBlockNumber)
                    if (!matches) {
                        assertNull(storedNull.withdrawBlockHash)
                        assertNull(storedNull.withdrawEpochHex)
                        assertNull(storedNull.unlockEpochHex)
                        assertEquals(0L, storedNull.compensation)
                        assertNull(storedHistoric.withdrawEpochHex)
                        assertNull(storedHistoric.unlockEpochHex)
                        assertEquals(0L, storedHistoric.compensation)
                    } else {
                        assertEquals(withdraw.hash, storedNull.withdrawBlockHash)
                        assertEquals(withdraw.epoch, storedNull.withdrawEpochHex)
                    }
                }
            }
        }
    }

    private fun duringRevalidation(action: suspend () -> Unit) {
        val realReader = reader
        val scheduled = spyk(realReader)
        coEvery { scheduled.revalidateCachedWithdrawing(any(), any(), any()) } coAnswers {
            val result = realReader.revalidateCachedWithdrawing(firstArg(), secondArg(), thirdArg())
            action()
            result
        }
        gateway = DaoGateway(db, sync, scheduled, resolver, LightClientReadOnly(api, json, NoopLogger),
            mockk(relaxed = true), mockk(relaxed = true), NoopLogger, gate, recovery)
    }

    private fun unresolved(position: DaoDeposit) {
        assertEquals(DaoCellStatus.LOCKED, position.status)
        assertEquals("", position.depositBlockHash)
        assertNull(position.depositEpoch)
        assertNull(position.withdrawBlockHash)
        assertNull(position.withdrawEpoch)
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
