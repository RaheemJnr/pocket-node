package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import com.rjnr.pocketnode.data.storage.FakePendingBroadcastStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cold-start reconciliation of the pending rows that predate
 * `pending_broadcasts` (#115).
 *
 * The rule that matters: a TRANSIENT lookup failure must never mark a row
 * FAILED. The light client answers null for a tx it simply has not indexed
 * yet, and an old ghost row wrongly retired to FAILED can never come back.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupReconcilerTest {

    private val broadcasts = FakePendingBroadcastStore()
    private val transactions = FakeTransactionStore()

    private fun reconciler() = StartupReconciler(broadcasts, transactions, NoopLogger)

    @Test
    fun `an orphan the chain has committed is retired to confirmed`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            Result.success(
                TransactionStatusResponse(
                    txHash = it,
                    status = "committed",
                    confirmations = 3,
                    blockHash = "0xfb27201670e48f65b93b58c4cac7348c54554ad831ed5c1b386c9bd3c24fa911",
                )
            )
        }
        advanceUntilIdle()

        assertEquals(listOf(HASH to "CONFIRMED"), transactions.statusWrites)
    }

    @Test
    fun `an absent orphan remains pending`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            Result.success(
                TransactionStatusResponse(txHash = it, status = "unknown", confirmations = 0)
            )
        }
        advanceUntilIdle()

        assertTrue(transactions.statusWrites.isEmpty())
    }

    @Test
    fun `a transient lookup failure leaves the row pending for the next launch`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            Result.failure(IllegalStateException("light client not ready"))
        }
        advanceUntilIdle()

        assertTrue(transactions.statusWrites.isEmpty())
    }

    @Test
    fun `a tx still sitting in the pool keeps its pending row`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            Result.success(
                TransactionStatusResponse(txHash = it, status = "pending", confirmations = 0)
            )
        }
        advanceUntilIdle()

        assertTrue(transactions.statusWrites.isEmpty())
    }

    @Test
    fun `no orphans means the light client is never asked`() = runTest {
        broadcasts.seed(
            PendingBroadcastRecord(
                txHash = HASH,
                state = PendingBroadcastRecord.STATE_BROADCASTING,
                reservedInputs = "[]",
                signedTxJson = "{}",
                walletId = WALLET,
                network = NETWORK,
                submittedAtTipBlock = 0L,
                nullCount = 0,
                createdAt = 0L,
                lastCheckedAt = 0L,
            )
        )
        var asked = 0

        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            asked++
            Result.success(TransactionStatusResponse(txHash = it, status = "unknown"))
        }
        advanceUntilIdle()

        assertEquals(0, asked)
        assertTrue(transactions.statusWrites.isEmpty())
    }

    @Test
    fun `unknown orphan later included is recovered in this process`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)
        var calls = 0
        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            calls++
            Result.success(TransactionStatusResponse(txHash = it,
                status = if (calls == 1) "unknown" else "committed", confirmations = 1,
                blockHash = if (calls == 1) null else "0x" + "ab".repeat(32)))
        }
        advanceUntilIdle()
        assertEquals(listOf(HASH to "CONFIRMED"), transactions.statusWrites)
    }

    @Test
    fun `malformed or mismatched inclusion cannot settle an orphan`() = runTest {
        for (response in listOf(
            TransactionStatusResponse(txHash = HASH, status = "pending", confirmations = 1, blockHash = "0x" + "ab".repeat(32)),
            TransactionStatusResponse(txHash = HASH, status = "committed", confirmations = 0, blockHash = "0x" + "ab".repeat(32)),
            TransactionStatusResponse(txHash = HASH, status = "committed", confirmations = 1, blockHash = "0xbb"),
            TransactionStatusResponse(txHash = "other", status = "committed", confirmations = 1, blockHash = "0x" + "ab".repeat(32))
        )) {
            transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)
            reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) { Result.success(response) }
            advanceUntilIdle()
            assertTrue(transactions.statusWrites.isEmpty(), response.toString())
        }
    }

    @Test
    fun `unknown passes are bounded and retain the orphan`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)
        var calls = 0
        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            calls++
            Result.success(TransactionStatusResponse(txHash = it, status = "unknown"))
        }
        advanceUntilIdle()
        assertEquals(4, calls)
        assertEquals(225_000L, testScheduler.currentTime)
        assertEquals(listOf(HASH), transactions.getOrphanPendingHashes(WALLET, NETWORK))
    }

    @Test
    fun `source switch during lookup discards even valid inclusion`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)
        var context = WALLET to NETWORK
        reconciler().reconcile(WALLET, NETWORK, this, { context }) {
            kotlinx.coroutines.delay(1)
            context = "other" to NETWORK
            Result.success(TransactionStatusResponse(txHash = it, status = "committed", confirmations = 1, blockHash = "0x" + "ab".repeat(32)))
        }
        advanceUntilIdle()
        assertEquals(emptyList(), transactions.statusWrites)
    }

    @Test
    fun `explicit rejection settles while malformed and unavailable siblings remain`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf("unavailable", HASH, "other")
        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            if (it == "unavailable") error("unavailable")
            Result.success(TransactionStatusResponse(txHash = HASH, status = "rejected"))
        }
        advanceUntilIdle()
        assertEquals(listOf(HASH to "FAILED"), transactions.statusWrites)
        assertEquals(listOf("unavailable", "other"), transactions.getOrphanPendingHashes(WALLET, NETWORK))
    }

    @Test
    fun `wrapped cancellation stops deferred work without writes`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)
        var calls = 0
        reconciler().reconcile(WALLET, NETWORK, this, { WALLET to NETWORK }) {
            calls++
            Result.failure(kotlinx.coroutines.CancellationException("source stopped"))
        }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(emptyList(), transactions.statusWrites)
    }

    private companion object {
        const val WALLET = "w1"
        const val NETWORK = "TESTNET"
        const val HASH =
            "0xa6789f42b0568b1872e5a5858f0c42148dd8d313f844252f5fe3dfe556958ba9"
    }
}
