package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.storage.FakePendingBroadcastStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Moved from `app/src/test` with the watchdog, from JUnit 4 +
 * Robolectric onto `kotlin.test` and the shared store fakes. Every assertion
 * is the one it was: the Robolectric runner was only there because the
 * watchdog used to log through `android.util.Log`, and it no longer does.
 */
class BroadcastWatchdogTest {

    private fun row(state: String = "BROADCAST", nullCount: Int = 0, submittedAt: Long = 100L) =
        PendingBroadcastRecord(
            txHash = "0xaa", walletId = "w1", network = "TESTNET",
            signedTxJson = "{}", reservedInputs = "[]", state = state,
            submittedAtTipBlock = submittedAt, nullCount = nullCount,
            createdAt = 0L, lastCheckedAt = 0L
        )

    private fun store(vararg rows: PendingBroadcastRecord) = FakePendingBroadcastStore().apply {
        rows.forEach { byHash[it.txHash] = it }
    }

    private fun watchdog(
        broadcasts: FakePendingBroadcastStore,
        statusGateway: TransactionStatusGateway,
        transactions: FakeTransactionStore,
        scheduler: TestCoroutineScheduler
    ) = BroadcastWatchdog(
        pendingBroadcasts = broadcasts,
        statusGateway = statusGateway,
        transactions = transactions,
        tipSource = FakeTipSource(),
        lifecycleProvider = { true },
        dispatcher = StandardTestDispatcher(scheduler),
        logger = NoopLogger,
        clock = FakeClock(),
    )

    @Test
    fun onChainResponseConfirmsAndDeletes() = runTest {
        val broadcasts = store(row(state = "BROADCAST"))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.OnChain("0xbb") }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        assertEquals(0, broadcasts.getActive("w1", "TESTNET").size)
        assertEquals("CONFIRMED", transactions.statusOf("0xaa"))
    }

    @Test
    fun inPoolResponsePromotesBroadcastingAndResetsNullCount() = runTest {
        val broadcasts = store(row(state = "BROADCASTING", nullCount = 2))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.InPool }, transactions, testScheduler)
        wd.checkAll(currentTip = 105L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(0, r.nullCount)
    }

    @Test
    fun inPoolPastThresholdFailsAndUpdatesTransactionsStatus() = runTest {
        // Light client mempool says "pending" indefinitely (e.g. dependency on a
        // never-landed tx); chain doesn't commit. Watchdog must time out instead
        // of leaving the row stuck-pending forever.
        val broadcasts = store(row(state = "BROADCAST", submittedAt = 100L))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.InPool }, transactions, testScheduler)
        // tip 130 >= 100 + 25
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.byHash.values.single()
        assertEquals("FAILED", r.state)
        assertEquals("FAILED", transactions.statusOf("0xaa"))
    }

    @Test
    fun nullBelowThresholdDoesNotFail() = runTest {
        val broadcasts = store(row(state = "BROADCAST", nullCount = 0))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(1, r.nullCount)
    }

    @Test
    fun nullAtThresholdButTipNotAdvancedDoesNotFail() = runTest {
        val broadcasts = store(row(state = "BROADCAST", nullCount = 3, submittedAt = 100L))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        // tip 120 < 100 + 25 = 125
        wd.checkAll(currentTip = 120L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(4, r.nullCount)
    }

    @Test
    fun nullAtThresholdAndTipAdvancedFailsAndUpdatesTransactionsStatus() = runTest {
        val broadcasts = store(row(state = "BROADCAST", nullCount = 3, submittedAt = 100L))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.byHash.values.single()
        assertEquals("FAILED", r.state)
        assertEquals("FAILED", transactions.statusOf("0xaa"))
    }

    @Test
    fun exceptionLeavesStateUnchanged() = runTest {
        val broadcasts = store(row(state = "BROADCAST", nullCount = 1))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.Exception }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.getActive("w1", "TESTNET").single()
        assertEquals("BROADCAST", r.state)
        assertEquals(1, r.nullCount)
    }

    // ----- cold start (#115 Task 5) -----
    //
    // A `BROADCASTING` row exists at app launch: the previous process died
    // between the INSERT and the bridge call returning. Phase A does not
    // auto-rebroadcast; the watchdog observes the row and resolves it through
    // the status gateway.

    private fun orphan(submittedAt: Long = 100L) = PendingBroadcastRecord(
        txHash = "0xaa", walletId = "w1", network = "TESTNET",
        signedTxJson = "{}", reservedInputs = "[]", state = "BROADCASTING",
        submittedAtTipBlock = submittedAt, nullCount = 0,
        createdAt = 0L, lastCheckedAt = 0L
    )

    @Test
    fun orphanResolvesToConfirmedWhenNodeReportsOnChain() = runTest {
        val broadcasts = store(orphan())
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.OnChain("0xbb") }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        assertEquals(0, broadcasts.getActive("w1", "TESTNET").size)
        assertEquals("CONFIRMED", transactions.statusOf("0xaa"))
    }

    @Test
    fun orphanUpgradesToBroadcastWhenNodeReportsInPool() = runTest {
        val broadcasts = store(orphan())
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.InPool }, transactions, testScheduler)
        wd.checkAll(currentTip = 105L, walletId = "w1", network = "TESTNET")
        assertEquals("BROADCAST", broadcasts.getActive("w1", "TESTNET").single().state)
    }

    @Test
    fun orphanStaysBroadcastingWithElevatedNullCountWhenUnknownAndTipNotAdvanced() = runTest {
        val broadcasts = store(orphan(submittedAt = 100L))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        wd.checkAll(currentTip = 110L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.getActive("w1", "TESTNET").single()
        assertEquals("BROADCASTING", r.state)
        assertEquals(1, r.nullCount)
        assertNull(transactions.statusOf("0xaa"))
    }

    @Test
    fun orphanResolvesToFailedAfterThresholdAndTipPastTimeout() = runTest {
        val broadcasts = store(orphan(submittedAt = 100L).copy(nullCount = 3))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.byHash.values.single()
        assertEquals("FAILED", r.state)
        assertEquals("FAILED", transactions.statusOf("0xaa"))
    }
}

/** Last status written for [hash], or null when the store was never asked. */
private fun FakeTransactionStore.statusOf(hash: String): String? =
    statusWrites.lastOrNull { it.first == hash }?.second

private class FakeTipSource : TipSource {
    private val _tip = MutableStateFlow(0L)
    override val tipFlow: StateFlow<Long> = _tip
    override suspend fun fetchAndPublishTip(): Long = 0L
    override fun activeWalletAndNetworkOrNull(): Pair<String, String>? = "w1" to "TESTNET"
}
