package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.*
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.SendContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room and typed bridge decoder. Only native authority responses are scripted. */
@RunWith(RobolectricTestRunner::class)
class DaoRecoveryCoordinatorTest {
    private val json = Json { encodeDefaults = true }
    private lateinit var db: AppDatabase
    private lateinit var gate: VerificationCacheGate
    private lateinit var recovery: DaoRecoveryCoordinator
    private lateinit var api: LightClientApi
    private val network = NetworkType.TESTNET
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
    private val txHash = hash("aa")
    private val genesis = hash("01")
    private val identity = DaoRecoveryIdentity(genesis, "e".repeat(32), "0x" + "02".repeat(16))
    private val point = OutPoint(txHash, "0x10") // 16, not decimal 10
    private lateinit var receipt: DaoRecoveryReceipt
    private var valid = true
    private var request = "dr1.q.01"

    @Before fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        gate = spyk(VerificationCacheGate(ApplicationProvider.getApplicationContext(), db))
        api = mockk(relaxed = true)
        every { api.callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"${identity.storeGeneration}"}}"""
        gate.reconcile(api, network)
        db.walletDao().insert(WalletEntity("wallet", "Synthetic", "raw_key", null, null, isActive = true))
        val header = DaoRecoveryHeader("0x0", "0x0", "0x1", "0x12c", "0x7080000000012c",
            hash("00"), hash("00"), hash("00"), hash("00"), hash("00"), "0x0", hash("cc"))
        receipt = DaoRecoveryReceipt(1, "raw_transaction_membership", "dr1.r.01", request, "dr1.a.01", identity,
            "1", header, txHash, DaoRecoveryRawTransaction("0x0", emptyList(), listOf(hash("dd")),
                List(17) { DaoRecoveryInput("0x0", OutPoint(hash("bb"), "0x0")) },
                List(17) { DaoRecoveryOutput("0x174876e800", script, DaoConstants.DAO_TYPE_SCRIPT) },
                List(17) { "0x6400000000000000" }), header, null)
        every { api.requestDaoRecovery(any()) } answers {
            """{"schema_version":1,"operation":"request","result":{"request_id":"$request","transaction_hash":"${firstArg<String>()}","identity":${json.encodeToString(identity)}}}"""
        }
        every { api.pollDaoRecovery(any()) } answers {
            """{"schema_version":1,"operation":"poll","result":{"request_id":"$request","status":"verified","receipt":${json.encodeToString(receipt)}}}"""
        }
        every { api.validateDaoRecovery(any(), any()) } answers {
            if (valid) """{"schema_version":1,"operation":"validate","result":{"request_id":"$request","valid":true,"receipt":${json.encodeToString(receipt)}}}"""
            else """{"schema_version":1,"operation":"validate","error":{"code":"invalid_receipt"}}"""
        }
        recovery = DaoRecoveryCoordinator(db, gate, api, json)
        db.daoCellDao().upsert(row())
    }
    @After fun close() { db.close() }

    private fun row(index: String = "0x10", status: String = "UNLOCKABLE") = DaoCellEntity(txHash, index,
        100_000_000_000L, status, 100, hash("dd"), "0x70800000000064", 200, hash("ee"),
        "0x708000000000c8", 123, "0x70800000000258", 999, network.name, 8, "wallet")
    private suspend fun begin() = recovery.begin("wallet", network, script, genesis, point)

    @Test fun `adoption repairs every alias including added pending and completed rows preserving identities`() = runTest {
        val before = gate.requireReadyRevision(network.name)
        val context = begin()
        db.daoCellDao().upsert(row("00010", "UNLOCKING").copy(withdrawBlockNumber = 250, withdrawBlockHash = hash("fe")))
        db.daoCellDao().upsert(row("10", "COMPLETED"))
        db.pendingDaoUnlockDao().upsert(PendingDaoUnlockEntity(txHash, "0010", hash("ab"), "wallet", network.name, 1))
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        val rows = db.daoCellDao().allRows()
        assertEquals(setOf("0x10", "00010", "10"), rows.map { it.index }.toSet())
        assertEquals(setOf("UNLOCKABLE", "UNLOCKING", "COMPLETED"), rows.map { it.status }.toSet())
        rows.forEach { assertEquals(300L, it.withdrawBlockNumber); assertEquals(hash("cc"), it.withdrawBlockHash)
            assertNull(it.depositEpochHex); assertNull(it.unlockEpochHex); assertEquals(0L, it.compensation)
            assertEquals(100L, it.depositBlockNumber); assertEquals(8L, it.lastUpdatedAt) }
        assertEquals(1, db.pendingDaoUnlockDao().getByWalletAndNetwork("wallet", network.name).size)
        assertEquals(1, recovery.guards(network, OutPoint(txHash.uppercase().replace("0X", "0x"), "00010")).size)
        assertTrue(gate.currentRevision() > before)
        assertTrue(runCatching { gate.commitIfCurrent(network.name, before) { db.daoCellDao().upsert(row()) } }.isFailure)
        assertEquals(300L, db.daoCellDao().allRows().first().withdrawBlockNumber)
    }

    @Test fun `SQL failure after native success rolls back aliases guard and revision then retries`() = runTest {
        val context = begin()
        db.daoCellDao().upsert(row("0010"))
        val before = gate.currentRevision()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_guard BEFORE INSERT ON dao_recovery_guards BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
        assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
        assertEquals(before, gate.currentRevision())
        assertTrue(recovery.guards(network, point).isEmpty())
        assertTrue(db.daoCellDao().allRows().all { it.withdrawBlockNumber == 200L && it.compensation == 123L })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_guard")
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        assertNotNull(recovery.validateGuard(context, point))
    }

    @Test fun `deleted groups and foreign wallet aliases cannot be recreated or repaired`() = runTest {
        val context = begin()
        db.daoCellDao().deleteAll()
        assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
        assertTrue(db.daoCellDao().allRows().isEmpty())
        db.daoCellDao().upsert(row())
        db.daoCellDao().upsert(row("0010").copy(walletId = "other"))
        assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
        assertTrue(recovery.guards(network, point).isEmpty())
        assertTrue(db.daoCellDao().allRows().all { it.withdrawBlockNumber == 200L })
    }

    @Test fun `changed capacity deposit identity and foreign network alias reject whole group`() = runTest {
        val context = begin()
        for (bad in listOf(row("0010").copy(capacity = 1), row("0010").copy(depositBlockNumber = 101),
            row("0010").copy(network = "MAINNET"))) {
            db.daoCellDao().upsert(bad)
            assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
            assertTrue(recovery.guards(network, point).isEmpty())
        }
    }

    @Test fun `reorg between native claim and SQL leaves durable unresolved obligation`() = runTest {
        val context = begin()
        coEvery { gate.commitRecoveryIfCurrent(any(), any(), any()) } coAnswers {
            valid = false
            callOriginal()
        }
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        assertEquals(300L, db.daoCellDao().allRows().single().withdrawBlockNumber)
        assertEquals(1, recovery.guards(network, point).size)
        assertNull(recovery.validateGuard(context, point))
        assertTrue(recovery.invalidation.value > 0)
    }

    @Test fun `restart cache clearing and native generation changes retain obligation without authority`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        db.daoCellDao().deleteAll()
        db.daoCellDao().upsert(row())
        val restarted = DaoRecoveryCoordinator(db, gate, api, json)
        assertNull(restarted.validateGuard(network, point, "wallet"))
        every { api.callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"${"f".repeat(32)}"}}"""
        gate.reconcile(api, network)
        assertEquals(1, recovery.guards(network, point).size)
        assertNull(recovery.validateGuard(context, point))
        assertTrue(db.daoCellDao().allRows().isEmpty())
    }

    @Test fun `wallet switching gate closure and cancellation invalidate live receipt`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        db.walletDao().deactivateAll()
        assertNull(recovery.validateGuard(context, point))
        assertEquals(1, recovery.guards(network, point).size)
        db.walletDao().activate("wallet")
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        recovery.cancel(context)
        assertNull(recovery.validateGuard(context, point))
        assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
        gate.close()
        assertNull(recovery.validateGuard(context, point))
    }

    @Test fun `adopting second output fences staged reads without revoking first valid proof`() = runTest {
        val first = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(first))
        db.daoCellDao().upsert(row("0x0"))
        val second = recovery.begin("wallet", network, script, genesis, OutPoint(txHash, "0x0"))
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(second))
        assertNotNull(recovery.validateGuard(first, point))
        assertNotNull(recovery.validateGuard(second, OutPoint(txHash, "0x0")))
    }

    @Test fun `guarded submission pins locator and fails after native invalidation`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        val sendContext = SendContext(network, "wallet", script, { false }, this)
        val tx = Transaction(cellDeps = emptyList(), headerDeps = listOf(hash("dd"), hash("cc")),
            cellInputs = listOf(CellInput(previousOutput = point)), cellOutputs = listOf(CellOutput("0x174876e800", script)),
            outputsData = listOf("0x"), witnesses = listOf("0x"))
        val authorization = recovery.authorize(sendContext, tx)
        authorization.requireCurrent()
        valid = false
        assertTrue(runCatching { authorization.requireCurrent() }.isFailure)
        assertTrue(runCatching { recovery.authorize(sendContext, tx) }.isFailure)
    }

    @Test fun `completed or removed guarded input and changed signed locator refuse submission`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        val sendContext = SendContext(network, "wallet", script, { false }, this)
        val tx = Transaction(cellDeps = emptyList(), headerDeps = listOf(hash("dd"), hash("cc")),
            cellInputs = listOf(CellInput(previousOutput = point)), cellOutputs = listOf(CellOutput("0x174876e800", script)),
            outputsData = listOf("0x"), witnesses = listOf("0x"))
        assertTrue(runCatching { recovery.authorize(sendContext, tx.copy(headerDeps = listOf(hash("dd"), hash("ee")))) }.isFailure)
        assertTrue(runCatching { recovery.authorize(sendContext, tx.copy(headerDeps = listOf(hash("dd"), hash("ee"), hash("cc")))) }.isFailure)
        val authorization = recovery.authorize(sendContext, tx)
        db.daoCellDao().upsert(db.daoCellDao().allRows().single().copy(index = "0010", status = "COMPLETED"))
        assertTrue(runCatching { authorization.requireCurrent() }.isFailure)
        db.daoCellDao().deleteAll()
        assertTrue(runCatching { recovery.authorize(sendContext, tx) }.isFailure)
    }

    @Test fun `reset marker and coroutine cancellation cannot leave active authorization`() = runTest {
        val context = begin()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        every { api.pollDaoRecovery(any()) } answers {
            entered.complete(Unit)
            kotlinx.coroutines.runBlocking { release.await() }
            """{"schema_version":1,"operation":"poll","result":{"request_id":"$request","status":"verified","receipt":${json.encodeToString(receipt)}}}"""
        }
        val job = async { recovery.recover(context) }
        entered.await(); job.cancel(); release.complete(Unit)
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(recovery.guards(network, point).isEmpty())
        assertEquals(200L, db.daoCellDao().allRows().single().withdrawBlockNumber)
        assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
        db.walletStorage.beginReset()
        assertNull(recovery.validateGuard(context, point))
        assertEquals(RecoveryOutcome.Unavailable, recovery.recover(context))
    }

    @Test fun `projection after process restart never authorizes persisted derived values`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        // Even a preexisting derived row is not authorization after a new coordinator starts.
        db.daoCellDao().upsert(row().copy(withdrawBlockNumber = 300, withdrawBlockHash = hash("cc")))
        val restarted = DaoRecoveryCoordinator(db, gate, api, json)
        val projected = db.daoCellDao().allRows().single().toOutsideWindowDeposit(restarted)
        assertNull(projected.unlockEpoch)
        assertNull(projected.withdrawBlockHash)
        assertEquals(0L, projected.compensation)
        assertTrue(projected.outsideSyncWindow)
    }

    @Test fun `cancellation on Room transaction return cannot leave committed repair with current old ticket`() = runTest {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var afterTransaction: (() -> Unit)? = null
        val isolated = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .setTransactionExecutor { task -> executor.execute { task.run(); afterTransaction?.invoke() } }.build()
        try {
            val isolatedGate = VerificationCacheGate(ApplicationProvider.getApplicationContext(), isolated)
            isolatedGate.reconcile(api, network)
            val ticket = isolatedGate.requireReadyRevision(network.name)
            val operation = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                isolatedGate.commitRecoveryIfCurrent(network.name, ticket) {
                    isolated.daoCellDao().upsert(row().copy(withdrawBlockNumber = 300))
                }
            }
            afterTransaction = { operation.cancel() }
            operation.start()
            runCatching { operation.await() }
            afterTransaction = null
            assertEquals(300L, isolated.daoCellDao().allRows().single().withdrawBlockNumber)
            assertTrue("committed SQL must fence old staged work even if return is cancelled", isolatedGate.currentRevision() > ticket)
        } finally { isolated.close(); executor.shutdownNow() }
    }

    @Test fun `normalization uses hexadecimal multi digit leading zero and rejects unsigned overflow`() {
        assertEquals(16L, RecoveryOutPoint.from(OutPoint(txHash, "10")).outputIndex)
        assertEquals(16L, RecoveryOutPoint.from(OutPoint(txHash, "0x00010")).outputIndex)
        assertEquals(255L, RecoveryOutPoint.from(OutPoint(txHash, "0x00ff")).outputIndex)
        for (bad in listOf("-1", "+1", "0x100000000", "8000000000000000", "0x")) {
            assertTrue(runCatching { RecoveryOutPoint.from(OutPoint(txHash, bad)) }.isFailure)
        }
    }

    companion object { private fun hash(pair: String) = "0x" + pair.repeat(32) }
}
