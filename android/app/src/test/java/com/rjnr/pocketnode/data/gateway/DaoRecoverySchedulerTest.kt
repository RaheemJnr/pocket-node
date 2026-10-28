package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room budget, synthetic work, virtual monotonic time. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DaoRecoverySchedulerTest {
    private lateinit var db: AppDatabase
    private val point = RecoveryOutPoint("0x" + "aa".repeat(32), 16)
    private val session = RecoverySession("wallet", NetworkType.TESTNET, "0x" + "01".repeat(32),
        Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20)), 1)
    @Before fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        db.walletDao().insert(WalletEntity("wallet", "Synthetic", "raw_key", null, null, isActive = true))
    }
    @After fun close() { db.close() }
    private class Clock(val scheduler: TestCoroutineScheduler) : RecoveryClock {
        var wall = 1_000_000L
        override fun elapsed() = scheduler.currentTime
        override fun wall() = wall + scheduler.currentTime
    }
    @Test fun `three deadlines and backoffs exhaust cycle then persist ten minute cooldown`() = runTest {
        val clock = Clock(testScheduler)
        val budget = DaoRecoveryBudget(db, clock)
        val runner = DaoRecoveryQueue(budget)
        val starts = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.run(session, { listOf(point) }) { starts += currentTime; awaitCancellation() }
        }
        runCurrent()
        assertEquals(listOf(0L), starts)
        advanceTimeBy(39_999); runCurrent(); assertEquals(1, starts.size)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(0L, 40_000L), starts)
        advanceTimeBy(60_000); runCurrent(); assertEquals(listOf(0L, 40_000L, 100_000L), starts)
        advanceTimeBy(629_999); runCurrent(); assertEquals(3, starts.size)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(0L, 40_000L, 100_000L, 730_000L), starts)
        assertEquals(1, db.daoRecoveryDao().budget("wallet", "TESTNET", session.genesis, point.transactionHash, 16)!!.attempts)
        job.cancelAndJoin()
    }
    @Test fun `repeated candidates coalesce and native work sees committed debit before starting`() = runTest {
        val clock = Clock(testScheduler)
        val runner = DaoRecoveryQueue(DaoRecoveryBudget(db, clock))
        var starts = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.run(session, { List(200) { point } }) {
                starts++
                assertEquals(1, db.daoRecoveryDao().budget("wallet", "TESTNET", session.genesis, point.transactionHash, 16)!!.attempts)
                awaitCancellation()
            }
        }
        repeat(20) { advanceTimeBy(1000); runCurrent() }
        assertEquals(1, starts)
        assertEquals(point, runner.checking.value)
        job.cancelAndJoin()
        assertNull(runner.checking.value)
    }
    @Test fun `restart replays persisted interval and wall jumps never accelerate live eligibility`() = runTest {
        val clock = Clock(testScheduler)
        val budget = DaoRecoveryBudget(db, clock)
        val key = RecoveryBudgetKey(session, point)
        val debit = budget.debit(key)!!
        budget.finish(debit)
        clock.wall += 86_400_000
        assertNull(budget.debit(key))
        advanceTimeBy(9_999); assertNull(budget.debit(key))
        clock.wall -= 172_800_000
        advanceTimeBy(1); assertNotNull(budget.debit(key))
        val restarted = DaoRecoveryBudget(db, clock)
        assertNull(restarted.debit(key))
        advanceTimeBy(59_999); assertNull(restarted.debit(key))
        advanceTimeBy(1); assertEquals(3, restarted.debit(key)!!.record.attempts)
    }
    @Test fun `screen recreation retains clock anchor and cancelled debit cannot resurrect deleted budget`() = runTest {
        val clock = Clock(testScheduler)
        val budget = DaoRecoveryBudget(db, clock)
        val key = RecoveryBudgetKey(session, point)
        val debit = budget.debit(key)!!
        advanceTimeBy(5_000)
        budget.finish(debit)
        advanceTimeBy(5_000)
        assertNull(budget.debit(key))
        advanceTimeBy(5_000)
        val next = budget.debit(key)!!
        db.daoRecoveryDao().deleteBudgetsForWallet("wallet")
        budget.finish(next)
        assertNull(db.daoRecoveryDao().budget("wallet", "TESTNET", session.genesis, point.transactionHash, 16))
    }
    @Test fun `more than bounded queue positions receive fair turns with one active operation`() = runTest {
        val runner = DaoRecoveryQueue(DaoRecoveryBudget(db, Clock(testScheduler)))
        val points = (0..139).map { point.copy(outputIndex = it.toLong()) }
        val visited = mutableListOf<RecoveryOutPoint>()
        var active = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.run(session, { points }) {
                active++; assertEquals(1, active)
                visited += it
                delay(1)
                active--
                false
            }
        }
        advanceTimeBy(1000); runCurrent()
        assertEquals(140, visited.toSet().size)
        assertEquals(points, visited.take(140))
        job.cancelAndJoin()
    }
    @Test fun `background ready wallet and network changes cancel current operation and consume debit`() = runTest {
        val runner = DaoRecoveryQueue(DaoRecoveryBudget(db, Clock(testScheduler)))
        val sessions = MutableStateFlow<RecoverySession?>(null)
        var starts = 0
        var cancelled = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.follow(sessions, { listOf(point) }) { _, _ ->
                starts++
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        runCurrent(); assertEquals(0, starts)
        sessions.value = session; runCurrent(); assertEquals(1, starts)
        sessions.value = null; runCurrent(); assertEquals(1, cancelled)
        sessions.value = session; runCurrent(); assertEquals(1, starts)
        advanceTimeBy(10_000); runCurrent(); assertEquals(2, starts)
        sessions.value = session.copy(network = NetworkType.MAINNET); runCurrent()
        assertEquals(2, cancelled); assertEquals(3, starts)
        sessions.value = session.copy(wallet = "other"); runCurrent()
        assertEquals(3, cancelled); assertEquals(3, starts)
        job.cancelAndJoin()
    }
    @Test fun `continuous invalidation exhausts attempts without weakening proof or immediate new cycle`() = runTest {
        val runner = DaoRecoveryQueue(DaoRecoveryBudget(db, Clock(testScheduler)))
        val starts = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.run(session, { listOf(point) }) {
                starts += currentTime
                delay(1_000) // A tip invalidates each proof before it can authorize anything.
                false
            }
        }
        advanceTimeBy(43_000); runCurrent()
        assertEquals(listOf(0L, 11_000L, 42_000L), starts)
        advanceTimeBy(599_999); runCurrent(); assertEquals(3, starts.size)
        advanceTimeBy(1); runCurrent(); assertEquals(643_000L, starts.last())
        job.cancelAndJoin()
    }
    @Test fun `failed durable debit starts no native work and leaves scheduler safely waiting`() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_budget BEFORE INSERT ON dao_recovery_retry_budget BEGIN SELECT RAISE(ABORT, 'synthetic budget write failure'); END")
        val runner = DaoRecoveryQueue(DaoRecoveryBudget(db, Clock(testScheduler)))
        var starts = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.run(session, { listOf(point) }) { starts++; false }
        }
        try {
            runCurrent()
            assertEquals(0, starts)
            assertTrue("storage failure must stay blocked without crashing the foreground owner", job.isActive)
            assertNull(runner.checking.value)
        } finally { job.cancelAndJoin(); db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_budget") }
    }
    @Test fun `completion bookkeeping refuses successor budget`() = runTest {
        val budget = DaoRecoveryBudget(db, Clock(testScheduler))
        val key = RecoveryBudgetKey(session, point)
        val old = budget.debit(key)!!
        val successor = old.record.copy(attempts = 3, nextEligibleAt = 123456789)
        db.daoRecoveryDao().putBudget(successor)
        budget.finish(old)
        assertEquals(successor, db.daoRecoveryDao().budget("wallet", "TESTNET", session.genesis, point.transactionHash, 16))
    }
}
