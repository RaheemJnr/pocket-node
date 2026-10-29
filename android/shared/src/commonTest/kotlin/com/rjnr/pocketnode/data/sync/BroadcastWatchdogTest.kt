package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.storage.FakePendingBroadcastStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import kotlinx.coroutines.async
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
        scheduler: TestCoroutineScheduler,
        tip: TipSource = FakeTipSource(),
        clock: FakeClock = FakeClock(),
    ) = BroadcastWatchdog(
        pendingBroadcasts = broadcasts.apply { activityStore = transactions },
        statusGateway = statusGateway,
        transactions = transactions,
        tipSource = tip,
        lifecycleProvider = { true },
        dispatcher = StandardTestDispatcher(scheduler),
        logger = NoopLogger,
        clock = clock,
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
    fun longPoolStayRetainsReservations() = runTest {
        // Elapsed blocks cannot prove rejection or release reserved inputs.
        val broadcasts = store(row(state = "BROADCAST", submittedAt = 100L))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.InPool }, transactions, testScheduler)
        // tip 130 >= 100 + 25
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.byHash.values.single()
        assertEquals("BROADCAST", r.state)
        assertNull(transactions.statusOf("0xaa"))
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
    fun repeatedAbsenceAtAdvancedTipRetainsReservations() = runTest {
        val broadcasts = store(row(state = "BROADCAST", nullCount = 3, submittedAt = 100L))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.byHash.values.single()
        assertEquals("BROADCAST", r.state)
        assertNull(transactions.statusOf("0xaa"))
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

    @Test
    fun cancellationPropagatesWithoutChangingState() = runTest {
        val broadcasts = store(row())
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { throw kotlinx.coroutines.CancellationException("cancel") }, transactions, testScheduler)
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            wd.checkAll(130L, "w1", "TESTNET")
        }
        assertEquals(row(), broadcasts.byHash["0xaa"])
        assertEquals(emptyList(), transactions.statusWrites)
    }

    @Test
    fun lateResponseCannotOverwriteAdvancedState() = runTest {
        val broadcasts = store(row())
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway {
            broadcasts.byHash["0xaa"] = row(state = "FAILED")
            TxFetchResult.OnChain("0xbb")
        }, transactions, testScheduler)
        wd.checkAll(130L, "w1", "TESTNET")
        assertEquals("FAILED", broadcasts.byHash["0xaa"]?.state)
        assertEquals(emptyList(), transactions.statusWrites)
    }

    @Test
    fun unknownReadsAreThrottledAndLaterInclusionRecovers() = runTest {
        val clock = FakeClock(0)
        val broadcasts = store(row())
        val transactions = FakeTransactionStore()
        var calls = 0
        var answer: TxFetchResult = TxFetchResult.NotFound
        val wd = watchdog(broadcasts, TransactionStatusGateway { calls++; answer }, transactions, testScheduler, clock = clock)
        repeat(100) { wd.checkAll(1_000_000L, "w1", "TESTNET") }
        assertEquals(1, calls)
        repeat(24) { clock.advance(15_000); wd.checkAll(1_000_000L, "w1", "TESTNET") }
        assertEquals("BROADCAST", broadcasts.byHash["0xaa"]?.state)
        assertNull(broadcasts.getFailedRow("0xaa"))
        val before = calls
        clock.advance(15_000); wd.checkAll(1_000_000L, "w1", "TESTNET")
        assertEquals(before, calls)
        clock.advance(45_000)
        answer = TxFetchResult.OnChain("0x" + "ab".repeat(32))
        wd.checkAll(1_000_000L, "w1", "TESTNET")
        assertEquals(before + 1, calls)
        assertNull(broadcasts.byHash["0xaa"])
        assertEquals("CONFIRMED", transactions.statusOf("0xaa"))
    }

    @Test
    fun changedWalletOrNetworkDiscardsEvidence() = runTest {
        for (replacement in listOf("w2" to "TESTNET", "w1" to "MAINNET", null)) {
            var context: Pair<String, String>? = "w1" to "TESTNET"
            val tip = object : TipSource {
                override val tipFlow = MutableStateFlow(0L)
                override suspend fun fetchAndPublishTip() = 0L
                override fun activeWalletAndNetworkOrNull() = context
            }
            val broadcasts = store(row())
            val transactions = FakeTransactionStore()
            val wd = watchdog(broadcasts, TransactionStatusGateway {
                kotlinx.coroutines.delay(1)
                context = replacement
                TxFetchResult.OnChain("0x" + "ab".repeat(32))
            }, transactions, testScheduler, tip)
            wd.checkAll(130L, "w1", "TESTNET")
            assertEquals(row(), broadcasts.byHash["0xaa"])
            assertEquals(emptyList(), transactions.statusWrites)
        }
    }

    @Test
    fun explicitRejectionSettlesOnlyItsRowAndLookupFailureDoesNotBlockSiblings() = runTest {
        val broadcasts = store(row(), row().copy(txHash = "0xbb"))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway {
            if (it == "0xaa") error("unavailable") else TxFetchResult.Rejected
        }, transactions, testScheduler)
        wd.checkAll(130L, "w1", "TESTNET")
        assertEquals("BROADCAST", broadcasts.byHash["0xaa"]?.state)
        assertEquals("FAILED", broadcasts.byHash["0xbb"]?.state)
        assertEquals(listOf("0xbb" to "FAILED"), transactions.statusWrites)
    }

    @Test
    fun concurrentPassesReadOnceAndDoNotOverwriteConfirmation() = runTest {
        val broadcasts = store(row())
        val transactions = FakeTransactionStore()
        var calls = 0
        val wd = watchdog(broadcasts, TransactionStatusGateway {
            calls++
            kotlinx.coroutines.delay(1_000)
            TxFetchResult.OnChain("0x" + "ab".repeat(32))
        }, transactions, testScheduler)
        val a = async { wd.checkAll(130L, "w1", "TESTNET") }
        val b = async { wd.checkAll(131L, "w1", "TESTNET") }
        a.await(); b.await()
        assertEquals(1, calls)
        assertEquals(listOf("0xaa" to "CONFIRMED"), transactions.statusWrites)
    }

    @Test
    fun stalledLookupTimesOutWithoutFailingOrBlockingSibling() = runTest {
        val broadcasts = store(row(), row().copy(txHash = "0xbb"))
        val wd = watchdog(broadcasts, TransactionStatusGateway {
            if (it == "0xaa") kotlinx.coroutines.delay(100_000)
            TxFetchResult.InPool
        }, FakeTransactionStore(), testScheduler)
        wd.checkAll(130L, "w1", "TESTNET")
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(2, broadcasts.getActive("w1", "TESTNET").size)
    }

    @Test
    fun statusMappingRequiresMatchingValidInclusionOrExplicitRejection() {
        val valid = com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse(
            txHash = "0xaa", status = "committed", confirmations = 1, blockHash = "0x" + "ab".repeat(32))
        kotlin.test.assertTrue(valid.toBroadcastObservation("0xaa") is TxFetchResult.OnChain)
        for (bad in listOf(valid.copy(txHash = "other"), valid.copy(status = "pending"),
            valid.copy(confirmations = 0), valid.copy(blockHash = null), valid.copy(blockHash = "0xbb"),
            valid.copy(blockHash = "0x" + "zz".repeat(32)))) {
            kotlin.test.assertFalse(bad.toBroadcastObservation("0xaa") is TxFetchResult.OnChain)
        }
        assertEquals(TxFetchResult.Rejected, valid.copy(status = "rejected").toBroadcastObservation("0xaa"))
        assertEquals(TxFetchResult.Exception, valid.copy(status = "rejected", txHash = "other").toBroadcastObservation("0xaa"))
        assertEquals(TxFetchResult.NotFound, valid.copy(status = "unknown").toBroadcastObservation("0xaa"))
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
    fun restartOrphanRemainsUnresolvedAfterThreshold() = runTest {
        val broadcasts = store(orphan(submittedAt = 100L).copy(nullCount = 3))
        val transactions = FakeTransactionStore()
        val wd = watchdog(broadcasts, TransactionStatusGateway { TxFetchResult.NotFound }, transactions, testScheduler)
        wd.checkAll(currentTip = 130L, walletId = "w1", network = "TESTNET")
        val r = broadcasts.byHash.values.single()
        assertEquals("BROADCASTING", r.state)
        assertNull(transactions.statusOf("0xaa"))
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
