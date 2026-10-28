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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room and typed bridge decoder. Only native authority responses are scripted. */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DaoRecoveryCoordinatorTest {
    private val json = Json { encodeDefaults = true }
    private lateinit var db: AppDatabase
    private lateinit var gate: VerificationCacheGate
    private lateinit var lifetime: DaoRecoveryLifetime
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
        lifetime = DaoRecoveryLifetime().apply { onStarted() }
        gate = spyk(VerificationCacheGate(ApplicationProvider.getApplicationContext(), db, lifetime))
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
        recovery = DaoRecoveryCoordinator(db, gate, api, json, lifetime)
        db.daoCellDao().upsert(row())
    }
    @After fun close() { db.close() }

    private fun row(index: String = "0x10", status: String = "UNLOCKABLE") = DaoCellEntity(txHash, index,
        100_000_000_000L, status, 100, hash("dd"), "0x70800000000064", 200, hash("ee"),
        "0x708000000000c8", 123, "0x70800000000258", 999, network.name, 8, "wallet")
    private suspend fun begin() = recovery.begin("wallet", network, script, genesis, point)

    private fun suitableNative() {
        every { api.status() } returns 1
        every { api.callRpc("get_peers") } returns """{"result":[{"protocols":[{"id":"0x78","version":"3"}],"sync_state":{"proved_best_known_header":{"hash":"${hash("cc")}","number":"0x12c"}}}]}"""
    }
    @Test fun `automatic selector keeps ordinary missing headers ordinary and excludes terminal and pending aliases`() = runTest {
        suitableNative()
        val scheduler = DaoRecoveryScheduler(db, recovery, api, gate, json)
        val session = RecoverySession("wallet", network, genesis, script, gate.lifecycleRevision())
        val positions = listOf(row().toUnverifiedCacheProjection())
        every { api.getHeaderByNumber(any()) } returns null
        assertTrue(scheduler.candidates(session, positions).isEmpty())
        val header = JniHeaderView(hash("cc"), "0xc8", "0x70800000000001", "0x1", hash("00"), hash("00"), hash("00"), hash("00"), hash("00"), "0x0")
        every { api.getHeaderByNumber(any()) } returns json.encodeToString(header)
        assertEquals(listOf(RecoveryOutPoint.from(point)), scheduler.candidates(session, positions))
        db.daoCellDao().upsert(row("00010"))
        assertEquals(1, scheduler.candidates(session, positions).size)
        for (status in listOf("UNLOCKING", "WITHDRAWING", "COMPLETED")) {
            db.daoCellDao().upsert(row("00010", status))
            assertTrue(scheduler.candidates(session, positions).isEmpty())
        }
    }
    @Test fun `guard without current receipt qualifies even with retained matching canonical maps`() = runTest {
        suitableNative()
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        val scheduler = DaoRecoveryScheduler(db, recovery, api, gate, json)
        val session = RecoverySession("wallet", network, genesis, script, gate.lifecycleRevision())
        val positions = db.daoCellDao().allRows().map { it.toUnverifiedCacheProjection() }
        assertTrue(scheduler.candidates(session, positions).isEmpty())
        valid = false
        assertEquals(listOf(RecoveryOutPoint.from(point)), scheduler.candidates(session, positions))
        every { api.callRpc("get_peers") } returns "{}"
        assertTrue(scheduler.candidates(session, positions).isEmpty())
    }

    @Test fun `foreground scheduler adopts then tip adapter revokes and leaving screen cancels authority`() = kotlinx.coroutines.runBlocking {
        suitableNative()
        every { api.getGenesisBlock() } returns """{"header":{"hash":"$genesis"}}"""
        val canonical = JniHeaderView(hash("cc"), "0xc8", "0x70800000000001", "0x1", hash("00"), hash("00"), hash("00"), hash("00"), hash("00"), "0x0")
        every { api.getHeaderByNumber(any()) } returns json.encodeToString(canonical)
        val wallet = kotlinx.coroutines.flow.MutableStateFlow<com.rjnr.pocketnode.data.wallet.WalletInfo?>(
            com.rjnr.pocketnode.data.wallet.WalletInfo("public", script, "test", "main"))
        val net = kotlinx.coroutines.flow.MutableStateFlow(network)
        val tip = kotlinx.coroutines.flow.MutableStateFlow(300L)
        val repository = mockk<GatewayRepository> {
            every { walletInfo } returns wallet
            every { this@mockk.network } returns net
            every { tipFlow } returns tip
            every { activeWalletAndNetworkOrNull() } returns ("wallet" to this@DaoRecoveryCoordinatorTest.network.name)
        }
        val positions = kotlinx.coroutines.flow.MutableStateFlow(listOf(row().toUnverifiedCacheProjection()))
        val refreshed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val scheduler = DaoRecoveryScheduler(db, recovery, api, gate, json)
        val job = launch { scheduler.foreground(repository, positions) { refreshed.send(Unit) } }
        try {
            kotlinx.coroutines.withTimeout(5_000) { refreshed.receive() }
            assertNotNull(recovery.validateGuard(network, point, "wallet"))
            verify(exactly = 1) { api.requestDaoRecovery(txHash) }
            valid = false
            tip.value = 301
            kotlinx.coroutines.withTimeout(5_000) { refreshed.receive() }
            assertNull(recovery.validateGuard(network, point, "wallet"))
            verify(exactly = 1) { api.requestDaoRecovery(txHash) }
        } finally { job.cancel(); job.join() }
        assertNull(scheduler.checking.value)
    }

    @Test fun `foreground cancellation owns allocated request until exact noncancellable cleanup before successor begins`() = kotlinx.coroutines.runBlocking {
        suitableNative()
        every { api.getGenesisBlock() } returns """{"header":{"hash":"$genesis"}}"""
        val canonical = JniHeaderView(hash("cc"), "0xc8", "0x70800000000001", "0x1", hash("00"), hash("00"), hash("00"), hash("00"), hash("00"), "0x0")
        every { api.getHeaderByNumber(any()) } returns json.encodeToString(canonical)
        db.daoCellDao().upsert(row("0x0"))
        val repository = mockk<GatewayRepository> {
            every { walletInfo } returns kotlinx.coroutines.flow.MutableStateFlow<com.rjnr.pocketnode.data.wallet.WalletInfo?>(
                com.rjnr.pocketnode.data.wallet.WalletInfo("public", script, "test", "main"))
            every { this@mockk.network } returns kotlinx.coroutines.flow.MutableStateFlow(this@DaoRecoveryCoordinatorTest.network)
            every { tipFlow } returns kotlinx.coroutines.flow.MutableStateFlow(300L)
            every { activeWalletAndNetworkOrNull() } returns ("wallet" to this@DaoRecoveryCoordinatorTest.network.name)
        }
        val firstAllocated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cleanupEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val successorAllocated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRequest = java.util.concurrent.CountDownLatch(1)
        val releaseCleanup = java.util.concurrent.CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val live = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val firstRequest = "dr1.q.11"
        val nextRequest = "dr1.q.22"
        every { api.requestDaoRecovery(txHash) } answers {
            val id = if (count.incrementAndGet() == 1) firstRequest else nextRequest
            events += "allocated:$id"
            live += id
            if (id == firstRequest) {
                firstAllocated.complete(Unit)
                check(releaseRequest.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } else successorAllocated.complete(Unit)
            """{"schema_version":1,"operation":"request","result":{"request_id":"$id","transaction_hash":"$txHash","identity":${json.encodeToString(identity)}}}"""
        }
        every { api.cancelDaoRecovery(any()) } answers {
            val id = firstArg<String>()
            if (id == firstRequest) {
                cleanupEntered.complete(Unit)
                check(releaseCleanup.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            live -= id
            events += "cancelled:$id"
            """{"schema_version":1,"operation":"cancel","result":{"request_id":"$id","status":"cancelled"}}"""
        }
        every { api.pollDaoRecovery(any()) } answers {
            """{"schema_version":1,"operation":"poll","result":{"request_id":"${firstArg<String>()}","status":"fetching","receipt":null}}"""
        }
        val scheduler = DaoRecoveryScheduler(db, recovery, api, gate, json)
        val firstPositions = kotlinx.coroutines.flow.MutableStateFlow(listOf(row().toUnverifiedCacheProjection()))
        val nextPositions = kotlinx.coroutines.flow.MutableStateFlow(listOf(row("0x0").toUnverifiedCacheProjection()))
        val owner = launch { scheduler.foreground(repository, firstPositions) {} }
        var successor: kotlinx.coroutines.Job? = null
        try {
            kotlinx.coroutines.withTimeout(5_000) { firstAllocated.await() }
            owner.cancel() // Native already allocated, but the IO result has not returned.
            successor = launch { scheduler.foreground(repository, nextPositions) {} }
            kotlinx.coroutines.yield()
            assertFalse(successorAllocated.isCompleted)
            releaseRequest.countDown()
            val ownsCleanup = kotlinx.coroutines.withTimeout(5_000) {
                kotlinx.coroutines.selects.select<Boolean> {
                    cleanupEntered.onAwait { true }
                    owner.onJoin { false }
                }
            }
            assertTrue("allocated ticket must be cancelled before foreground owner completes", ownsCleanup)
            assertFalse("cancellation must await exact native cleanup", owner.isCompleted)
            assertFalse("successor must wait until cleanup completes", successorAllocated.isCompleted)
            releaseCleanup.countDown()
            kotlinx.coroutines.withTimeout(5_000) { owner.join(); successorAllocated.await() }
            assertTrue(owner.isCancelled)
            assertEquals(listOf("allocated:$firstRequest", "cancelled:$firstRequest", "allocated:$nextRequest"), events.toList())
            assertEquals(setOf(nextRequest), live.toSet())
            assertTrue(recovery.guards(network, point).isEmpty())
        } finally {
            releaseRequest.countDown(); releaseCleanup.countDown()
            owner.cancel(); owner.join()
            successor?.cancel(); successor?.join()
        }
        assertTrue(live.isEmpty())
        verify(exactly = 1) { api.cancelDaoRecovery(firstRequest) }
        verify(exactly = 1) { api.cancelDaoRecovery(nextRequest) }
    }

    @Test fun `begin request null and error have no ticket to cancel while normal context remains usable`() = runTest {
        every { api.requestDaoRecovery(txHash) } returnsMany listOf(null,
            """{"schema_version":1,"operation":"request","error":{"code":"unavailable"}}""")
        repeat(2) {
            assertTrue(runCatching { begin() }.exceptionOrNull() is IllegalStateException)
        }
        verify(exactly = 0) { api.cancelDaoRecovery(any()) }
        every { api.requestDaoRecovery(txHash) } returns
            """{"schema_version":1,"operation":"request","result":{"request_id":"$request","transaction_hash":"$txHash","identity":${json.encodeToString(identity)}}}"""
        val context = begin()
        assertEquals(request, context.ticket.requestId)
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        assertNotNull(recovery.validateGuard(context, point))
        recovery.cancel(context)
        verify(exactly = 1) { api.cancelDaoRecovery(request) }
    }

    @Test fun `automatic adapter keeps native request stable through pending polls then requires exact receipt`() = runTest {
        var polls = 0
        every { api.pollDaoRecovery(any()) } answers {
            polls++
            if (polls <= 2) """{"schema_version":1,"operation":"poll","result":{"request_id":"$request","status":"fetching","receipt":null}}"""
            else """{"schema_version":1,"operation":"poll","result":{"request_id":"$request","status":"verified","receipt":${json.encodeToString(receipt)}}}"""
        }
        val runner = DaoRecoveryAttemptRunner(recovery)
        val session = RecoverySession("wallet", network, genesis, script, gate.lifecycleRevision())
        assertTrue(runner.run(session, RecoveryOutPoint.from(point)) { true })
        verify(exactly = 1) { api.requestDaoRecovery(txHash) }
        verify(exactly = 3) { api.pollDaoRecovery(request) }
        assertNotNull(recovery.validateGuard(network, point, "wallet"))
        runner.cancelAll()
        assertNotNull("completed receipt survives leaving DAO", recovery.validateGuard(network, point, "wallet"))
    }
    @Test fun `leaving DAO reaches actual Activity Retry with original bytes and refuses real invalidation`() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = androidx.lifecycle.ViewModelStore()
        try {
            suitableNative()
            every { api.getGenesisBlock() } returns """{"header":{"hash":"$genesis"}}"""
            val canonical = JniHeaderView(hash("cc"), "0xc8", "0x70800000000001", "0x1", hash("00"), hash("00"), hash("00"), hash("00"), hash("00"), "0x0")
            every { api.getHeaderByNumber(any()) } returns json.encodeToString(canonical)
            val repository = mockk<GatewayRepository>(relaxed = true) {
                every { walletInfo } returns kotlinx.coroutines.flow.MutableStateFlow<com.rjnr.pocketnode.data.wallet.WalletInfo?>(
                    com.rjnr.pocketnode.data.wallet.WalletInfo("public", script, "test", "main"))
                every { this@mockk.network } returns kotlinx.coroutines.flow.MutableStateFlow(this@DaoRecoveryCoordinatorTest.network)
                every { tipFlow } returns kotlinx.coroutines.flow.MutableStateFlow(300L)
                every { activeWalletAndNetworkOrNull() } returns ("wallet" to this@DaoRecoveryCoordinatorTest.network.name)
            }
            val tx = Transaction(cellDeps = emptyList(), headerDeps = listOf(hash("dd"), hash("cc")),
                cellInputs = listOf(CellInput(previousOutput = point)),
                cellOutputs = listOf(CellOutput("0x174876e800", script)), outputsData = listOf("0x"), witnesses = listOf("0x1234"))
            val builder = com.rjnr.pocketnode.data.transaction.TransactionBuilder(com.rjnr.pocketnode.data.validation.NetworkValidator())
            val hash = builder.computeTxHash(tx)
            val bytes = "  " + json.encodeToString(tx) + "  " // preserve literal stored formatting too
            val failed = PendingBroadcastEntity(hash, "wallet", network.name, bytes, json.encodeToString(listOf(point)), "FAILED", 300, 3, 7, 8)
            db.pendingBroadcastDao().insert(failed)
            val transactions = com.rjnr.pocketnode.data.storage.RoomTransactionStore(db.transactionDao(),
                CacheManager(db.transactionDao(), db.balanceCacheDao(), com.rjnr.pocketnode.core.log.NoopLogger, gate))
            transactions.insertPendingTransaction(hash, network.name, "wallet", "0x1", "dao_unlock", "0x0", 1)
            transactions.updateTransactionStatus(hash, "FAILED")
            val pipeline = com.rjnr.pocketnode.data.send.SendPipeline(api, builder, mockk(relaxed = true),
                com.rjnr.pocketnode.data.storage.RoomPendingBroadcastStore(db.pendingBroadcastDao()), transactions,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), json,
                com.rjnr.pocketnode.core.log.NoopLogger, recovery)
            every { api.getTipHeader() } returns null
            every { api.sendTransaction(any()) } returns hash
            val retried = kotlinx.coroutines.channels.Channel<Result<String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            coEvery { repository.retryBroadcast(hash) } coAnswers {
                pipeline.retryBroadcast(SendContext(network, "wallet", script, { true }, this@runBlocking), hash).also { retried.send(it) }
            }
            val scheduler = DaoRecoveryScheduler(db, recovery, api, gate, json)
            val adopted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val daoRoute = launch {
                scheduler.foreground(repository, kotlinx.coroutines.flow.MutableStateFlow(listOf(row().toUnverifiedCacheProjection()))) { adopted.complete(Unit) }
            }
            kotlinx.coroutines.withTimeout(5_000) { adopted.await() }
            daoRoute.cancel(); daoRoute.join() // same cancellation boundary as leaving RESUMED DaoScreen
            assertNotNull(recovery.validateGuard(network, point, "wallet"))
            coEvery { repository.getTransactions(any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
            val activity = com.rjnr.pocketnode.ui.screens.activity.ActivityViewModel(repository, db.transactionDao(), db.pendingBroadcastDao(),
                mockk(relaxed = true), mockk(relaxed = true), com.rjnr.pocketnode.core.log.NoopLogger, gate)
            store.put("activity", activity)
            activity.retryFailedTransaction(hash)
            assertTrue(retried.receive().isSuccess)
            verify(exactly = 1) { api.sendTransaction(bytes) }
            assertEquals(bytes, db.pendingBroadcastDao().getActive("wallet", network.name).single().signedTxJson)
            db.pendingBroadcastDao().insert(failed)
            valid = false
            recovery.refreshNativeValidity()
            activity.retryFailedTransaction(hash)
            assertTrue(retried.receive().isFailure)
            assertEquals(failed, db.pendingBroadcastDao().getFailedRow(hash))
            verify(exactly = 1) { api.sendTransaction(any()) }
        } finally { store.clear(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `configuration recreation retains receipt but real background and foreground cannot revive it`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        lifetime.onStopped(true)
        lifetime.onStarted()
        assertNotNull(recovery.validateGuard(context, point))
        lifetime.onStopped(false)
        assertTrue(runCatching { begin() }.isFailure)
        lifetime.onStarted()
        assertNull(recovery.validateGuard(context, point))
    }

    @Test fun `actual wallet switch away and back cannot revive completed receipt`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        db.walletDao().insert(WalletEntity("other", "Other synthetic", "raw_key", null, null, isActive = false))
        val keys = mockk<com.rjnr.pocketnode.data.wallet.KeyManager>(relaxed = true) {
            every { walletStorage } returns db.walletStorage
        }
        val wallets = com.rjnr.pocketnode.data.wallet.WalletRepository(db.walletDao(), keys,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), db, db.transactionDao(), db.balanceCacheDao(),
            db.daoCellDao(), db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(), db.subAccountCandidateDao(),
            mockk(relaxed = true), mockk(relaxed = true), com.rjnr.pocketnode.core.log.NoopLogger, lifetime)
        wallets.switchActiveWallet("other")
        wallets.switchActiveWallet("wallet")
        assertEquals("wallet", db.walletDao().getActive()?.walletId)
        assertNull(recovery.validateGuard(context, point))
        assertTrue(gate.isReady(network.name))
    }

    @Test fun `network close and reopen cannot revive completed receipt`() = runTest {
        val context = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(context))
        gate.reconcile(api, NetworkType.MAINNET)
        gate.reconcile(api, network)
        assertNull(recovery.validateGuard(context, point))
    }

    @Test fun `automatic adapter never treats adopted locator as current authority`() = runTest {
        var validations = 0
        every { api.validateDaoRecovery(any(), any()) } answers {
            validations++
            if (validations == 1) """{"schema_version":1,"operation":"validate","result":{"request_id":"$request","valid":true,"receipt":${json.encodeToString(receipt)}}}"""
            else """{"schema_version":1,"operation":"validate","error":{"code":"invalid_receipt"}}"""
        }
        val runner = DaoRecoveryAttemptRunner(recovery)
        assertFalse(runner.run(RecoverySession("wallet", network, genesis, script, gate.lifecycleRevision()), RecoveryOutPoint.from(point)) { true })
        assertEquals(300L, db.daoCellDao().allRows().single().withdrawBlockNumber)
        assertNull(recovery.validateGuard(network, point, "wallet"))
        runner.cancelAll()
    }

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
        val restarted = DaoRecoveryCoordinator(db, gate, api, json, lifetime)
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

    @Test fun `cancelled attempt cannot authorize replacement attempt for same output`() = runTest {
        val first = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(first))
        recovery.cancel(first)
        request = "dr1.q.02"
        receipt = receipt.copy(requestId = request, receiptId = "dr1.r.02")
        val replacement = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(replacement))
        assertNotNull(recovery.validateGuard(replacement, point))
        assertNull(recovery.validateGuard(first, point))
    }

    @Test fun `superseded uncancelled attempt cannot borrow successor receipt`() = runTest {
        val first = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(first))
        request = "dr1.q.02"
        receipt = receipt.copy(requestId = request, receiptId = "dr1.r.02")
        val replacement = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(replacement))
        assertNull(recovery.validateGuard(first, point))
        assertNotNull(recovery.validateGuard(replacement, point))
    }

    @Test fun `context cannot validate another adopted output`() = runTest {
        val first = begin()
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(first))
        val otherPoint = OutPoint(txHash, "0x0")
        db.daoCellDao().upsert(row("0x0"))
        val second = recovery.begin("wallet", network, script, genesis, otherPoint)
        assertEquals(RecoveryOutcome.Adopted, recovery.recover(second))
        assertNotNull(recovery.validateGuard(second, otherPoint))
        assertNull(recovery.validateGuard(first, otherPoint))
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
        val restarted = DaoRecoveryCoordinator(db, gate, api, json, lifetime)
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
