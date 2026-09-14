package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.gateway.TransactionStatusUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BroadcastWatchdogTest {

    private fun row(state: String = "BROADCAST", nullCount: Int = 0, submittedAt: Long = 100L) =
        PendingBroadcastEntity(
            txHash = "0xaa", walletId = "w1", network = "TESTNET",
            signedTxJson = "{}", reservedInputs = "[]", state = state,
            submittedAtTipBlock = submittedAt, nullCount = nullCount,
            createdAt = 0L, lastCheckedAt = 0L
        )

    private fun watchdog(
        dao: FakePendingBroadcastDao,
        statusGateway: TransactionStatusGateway,
        cache: FakeCacheManager,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    ) = BroadcastWatchdog(
        dao = dao,
        statusGateway = statusGateway,
        cache = cache,
        tipSource = FakeTipSource(),
        lifecycleProvider = { true },
        dispatcher = StandardTestDispatcher(scheduler),
        logger = NoopLogger
    )

    @Test
    fun `on-chain response confirms and deletes`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCAST")))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.OnChain("0xbb") }, cache, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        assertEquals(0, dao.getActive("w1", "TESTNET").size)
        assertEquals("CONFIRMED", cache.statusUpdates["0xaa"])
    }

    @Test
    fun `in-pool response promotes BROADCASTING and resets nullCount`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCASTING", nullCount = 2)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.InPool }, cache, testScheduler)
        wd.checkAll(currentTip = 105L, walletId = "w1", network = "TESTNET")
        val r = dao.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(0, r.nullCount)
    }

    @Test
    fun `in-pool past threshold fails and updates transactions status`() = runTest {
        // Light client mempool says "pending" indefinitely (e.g. dependency on a
        // never-landed tx); chain doesn't commit. Watchdog must time out instead
        // of leaving the row stuck-pending forever.
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCAST", submittedAt = 100L)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.InPool }, cache, testScheduler)
        // tip 130 >= 100 + 25
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = dao.allRows.single()
        assertEquals("FAILED", r.state)
        assertEquals("FAILED", cache.statusUpdates["0xaa"])
    }

    @Test
    fun `null below threshold does not fail`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCAST", nullCount = 0)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.NotFound }, cache, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = dao.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(1, r.nullCount)
    }

    @Test
    fun `null at threshold but tip not advanced does not fail`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCAST", nullCount = 3, submittedAt = 100L)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.NotFound }, cache, testScheduler)
        // tip 120 < 100 + 25 = 125
        wd.checkAll(currentTip = 120L, walletId = "w1", network = "TESTNET")
        val r = dao.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(4, r.nullCount)
    }

    @Test
    fun `null at threshold and tip advanced fails and updates transactions status`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCAST", nullCount = 3, submittedAt = 100L)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.NotFound }, cache, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = dao.allRows.single()
        assertEquals("FAILED", r.state)
        assertEquals("FAILED", cache.statusUpdates["0xaa"])
    }

    @Test
    fun `exception leaves state unchanged`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(row(state = "BROADCAST", nullCount = 1)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.Exception }, cache, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = dao.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(1, r.nullCount)
    }
}

// ----- fakes -----

class FakePendingBroadcastDao(
    initial: List<PendingBroadcastEntity> = emptyList()
) : PendingBroadcastDao {
    private val rows = initial.toMutableList()
    val allRows: List<PendingBroadcastEntity> get() = rows.toList()
    override suspend fun insert(row: PendingBroadcastEntity) {
        rows.removeAll { it.txHash == row.txHash }; rows.add(row)
    }
    override suspend fun delete(hash: String) { rows.removeAll { it.txHash == hash } }
    override suspend fun getActive(walletId: String, network: String) =
        rows.filter { it.walletId == walletId && it.network == network && it.state in setOf("BROADCASTING","BROADCAST") }
    override suspend fun compareAndUpdateState(hash: String, expected: String, next: String, now: Long): Int {
        val idx = rows.indexOfFirst { it.txHash == hash && it.state == expected }
        if (idx < 0) return 0
        rows[idx] = rows[idx].copy(state = next, lastCheckedAt = now)
        return 1
    }
    override suspend fun updateNullCount(hash: String, count: Int, now: Long) {
        val idx = rows.indexOfFirst { it.txHash == hash }
        if (idx >= 0) rows[idx] = rows[idx].copy(nullCount = count, lastCheckedAt = now)
    }
    override fun observeActive(walletId: String, network: String) =
        throw NotImplementedError("watchdog uses snapshot getActive only")
    override fun observeFailed(walletId: String, network: String) =
        throw NotImplementedError("not used by watchdog")
    override suspend fun getFailedRow(hash: String): PendingBroadcastEntity? =
        rows.firstOrNull { it.txHash == hash && it.state == "FAILED" }
}

class FakeCacheManager : TransactionStatusUpdater {
    val statusUpdates = mutableMapOf<String, String>()
    override suspend fun updateTransactionStatus(hash: String, status: String) {
        statusUpdates[hash] = status
    }
}

class FakeTipSource : com.rjnr.pocketnode.data.gateway.TipSource {
    private val _tip = MutableStateFlow(0L)
    override val tipFlow = _tip
    override suspend fun fetchAndPublishTip(): Long = 0L
    override fun activeWalletAndNetworkOrNull(): Pair<String, String>? = "w1" to "TESTNET"
}
