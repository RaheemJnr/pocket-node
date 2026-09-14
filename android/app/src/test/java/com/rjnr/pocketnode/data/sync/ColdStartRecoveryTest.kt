package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cold-start scenario: a `BROADCASTING` row exists at app launch (the
 * previous process died between INSERT and the JNI call returning).
 * Phase A does not auto-rebroadcast; the watchdog observes the row
 * and resolves it via `nativeGetTransaction`.
 *
 * Reuses the fakes defined in BroadcastWatchdogTest (same package).
 * Robolectric runner required because BroadcastWatchdog logs via
 * android.util.Log on the exception branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ColdStartRecoveryTest {

    private fun orphan(submittedAt: Long = 100L) = PendingBroadcastEntity(
        txHash = "0xaa", walletId = "w1", network = "TESTNET",
        signedTxJson = "{}", reservedInputs = "[]", state = "BROADCASTING",
        submittedAtTipBlock = submittedAt, nullCount = 0,
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
        lifecycleProvider = LifecycleProvider { true },
        dispatcher = StandardTestDispatcher(scheduler),
        logger = NoopLogger
    )

    @Test
    fun `orphan resolves to CONFIRMED when JNI reports on-chain`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(orphan()))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.OnChain("0xbb") }, cache, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        assertEquals(0, dao.getActive("w1", "TESTNET").size)
        assertEquals("CONFIRMED", cache.statusUpdates["0xaa"])
    }

    @Test
    fun `orphan upgrades to BROADCAST when JNI reports in-pool`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(orphan()))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.InPool }, cache, testScheduler)
        wd.checkAll(currentTip = 105L, walletId = "w1", network = "TESTNET")
        assertEquals("BROADCAST", dao.getActive("w1", "TESTNET").single().state)
    }

    @Test
    fun `orphan stays BROADCASTING with elevated nullCount when JNI null and tip not advanced`() = runTest {
        val dao = FakePendingBroadcastDao(initial = listOf(orphan(submittedAt = 100L)))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.NotFound }, cache, testScheduler)
        wd.checkAll(currentTip = 110L, walletId = "w1", network = "TESTNET")
        val r = dao.getActive("w1", "TESTNET").single()
        assertEquals("BROADCASTING", r.state)
        assertEquals(1, r.nullCount)
        assertNull(cache.statusUpdates["0xaa"])
    }

    @Test
    fun `orphan resolves to FAILED after threshold and tip past timeout`() = runTest {
        val seeded = orphan(submittedAt = 100L).copy(nullCount = 3)
        val dao = FakePendingBroadcastDao(initial = listOf(seeded))
        val cache = FakeCacheManager()
        val wd = watchdog(dao, TransactionStatusGateway { TxFetchResult.NotFound }, cache, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = dao.allRows.single()
        assertEquals("FAILED", r.state)
        assertEquals("FAILED", cache.statusUpdates["0xaa"])
    }
}
