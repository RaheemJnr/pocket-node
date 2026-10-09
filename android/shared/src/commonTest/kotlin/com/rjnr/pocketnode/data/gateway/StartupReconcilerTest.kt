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
class StartupReconcilerTest {

    private val broadcasts = FakePendingBroadcastStore()
    private val transactions = FakeTransactionStore()

    private fun reconciler() = StartupReconciler(broadcasts, transactions, NoopLogger)

    @Test
    fun `an orphan the chain has committed is retired to confirmed`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this) {
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
    fun `an orphan the light client has never heard of is retired to failed`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this) {
            Result.success(
                TransactionStatusResponse(txHash = it, status = "unknown", confirmations = 0)
            )
        }
        advanceUntilIdle()

        assertEquals(listOf(HASH to "FAILED"), transactions.statusWrites)
    }

    @Test
    fun `a transient lookup failure leaves the row pending for the next launch`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this) {
            Result.failure(IllegalStateException("light client not ready"))
        }
        advanceUntilIdle()

        assertTrue(transactions.statusWrites.isEmpty())
    }

    @Test
    fun `a tx still sitting in the pool keeps its pending row`() = runTest {
        transactions.orphanPending[WALLET to NETWORK] = listOf(HASH)

        reconciler().reconcile(WALLET, NETWORK, this) {
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

        reconciler().reconcile(WALLET, NETWORK, this) {
            asked++
            Result.success(TransactionStatusResponse(txHash = it, status = "unknown"))
        }
        advanceUntilIdle()

        assertEquals(0, asked)
        assertTrue(transactions.statusWrites.isEmpty())
    }

    private companion object {
        const val WALLET = "w1"
        const val NETWORK = "TESTNET"
        const val HASH =
            "0xa6789f42b0568b1872e5a5858f0c42148dd8d313f844252f5fe3dfe556958ba9"
    }
}
