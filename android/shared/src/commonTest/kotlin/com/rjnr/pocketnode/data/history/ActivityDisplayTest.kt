package com.rjnr.pocketnode.data.history

import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure display rules, which are the part of the activity list worth
 * testing once rather than once per platform.
 */
class ActivityDisplayTest {

    private fun record(
        txHash: String = "0xabc",
        status: String = "CONFIRMED",
        confirmations: Int = 3,
        timestamp: Long = 0L,
        direction: String = "out",
    ) = TransactionRecord(
        txHash = txHash,
        blockNumber = "0x10",
        blockHash = "0xdead",
        timestamp = timestamp,
        balanceChange = "0x1",
        direction = direction,
        fee = "0x0",
        confirmations = confirmations,
        status = status,
    )

    private fun broadcast(
        txHash: String = "0xabc",
        state: String = "BROADCAST",
        nullCount: Int = 0,
        createdAt: Long = 0L,
    ) = PendingBroadcastRecord(
        txHash = txHash,
        state = state,
        reservedInputs = "[]",
        signedTxJson = "{}",
        walletId = "w1",
        network = "TESTNET",
        submittedAtTipBlock = 100L,
        nullCount = nullCount,
        createdAt = createdAt,
        lastCheckedAt = createdAt,
    )

    // --- displayStateOf ---

    @Test
    fun `a failed ledger row is failed whatever the broadcast row says`() {
        assertEquals(
            TxDisplayState.FAILED,
            displayStateOf(record(status = "FAILED", confirmations = 0), broadcast(state = "BROADCAST")),
        )
    }

    @Test
    fun `a failed broadcast row is failed even before the ledger row catches up`() {
        // The watchdog writes the ledger row first, so a half-applied pair has
        // to read as failed from either side.
        assertEquals(
            TxDisplayState.FAILED,
            displayStateOf(record(status = "PENDING", confirmations = 0), broadcast(state = "FAILED")),
        )
    }

    @Test
    fun `failure outranks confirmation`() {
        assertEquals(
            TxDisplayState.FAILED,
            displayStateOf(record(status = "FAILED", confirmations = 9), null),
        )
    }

    @Test
    fun `any confirmation is confirmed`() {
        assertEquals(
            TxDisplayState.CONFIRMED,
            displayStateOf(record(status = "PENDING", confirmations = 1), broadcast(state = "BROADCASTING")),
        )
    }

    @Test
    fun `a confirmed status with no confirmations is still confirmed`() {
        assertEquals(
            TxDisplayState.CONFIRMED,
            displayStateOf(record(status = "CONFIRMED", confirmations = 0), null),
        )
    }

    @Test
    fun `only a BROADCASTING row reads as broadcasting`() {
        assertEquals(
            TxDisplayState.BROADCASTING,
            displayStateOf(record(status = "PENDING", confirmations = 0), broadcast(state = "BROADCASTING")),
        )
    }

    @Test
    fun `a BROADCAST row renders as pending`() {
        // The light client reports "in pool" for both, so surfacing a separate
        // Broadcast state would be surfacing a distinction the data cannot make.
        assertEquals(
            TxDisplayState.PENDING,
            displayStateOf(record(status = "PENDING", confirmations = 0), broadcast(state = "BROADCAST")),
        )
    }

    @Test
    fun `no broadcast row and no confirmations is pending`() {
        assertEquals(
            TxDisplayState.PENDING,
            displayStateOf(record(status = "PENDING", confirmations = 0), null),
        )
    }

    @Test
    fun `elapsed is shown only while in flight`() {
        assertTrue(showsElapsed(TxDisplayState.BROADCASTING))
        assertTrue(showsElapsed(TxDisplayState.PENDING))
        assertFalse(showsElapsed(TxDisplayState.CONFIRMED))
        assertFalse(showsElapsed(TxDisplayState.FAILED))
    }

    // --- filters ---

    @Test
    fun `the filters keep the Android direction sets`() {
        assertEquals(emptyList(), ActivityFilter.ALL.directions)
        assertEquals(listOf("in", "dao_unlock"), ActivityFilter.RECEIVED.directions)
        assertEquals(
            listOf("out", "self", "dao_deposit", "dao_withdraw"),
            ActivityFilter.SENT.directions,
        )
    }

    @Test
    fun `every direction the wallet produces lands in exactly one tab`() {
        val directions = listOf("in", "out", "self", "dao_deposit", "dao_withdraw", "dao_unlock")
        directions.forEach { direction ->
            val tabs = listOf(ActivityFilter.RECEIVED, ActivityFilter.SENT)
                .count { direction in it.directions }
            assertEquals(1, tabs, "$direction should appear in exactly one tab")
        }
    }

    // --- pendingSince ---

    @Test
    fun `the clock starts at the createdAt on the broadcast row when it has one`() {
        assertEquals(
            5_000L,
            pendingSince(record(timestamp = 9_000L), broadcast(createdAt = 5_000L)),
        )
    }

    @Test
    fun `it falls back to the ledger timestamp when the broadcast row has no createdAt`() {
        assertEquals(
            9_000L,
            pendingSince(record(timestamp = 9_000L), broadcast(createdAt = 0L)),
        )
    }

    @Test
    fun `it answers null rather than inventing zero`() {
        assertNull(pendingSince(record(timestamp = 0L), null))
        assertNull(pendingSince(record(timestamp = 0L), broadcast(createdAt = 0L)))
    }

    // --- elapsedBucket ---

    @Test
    fun `elapsed buckets by minute and hour and day`() {
        assertEquals(ElapsedBucket(ElapsedUnit.UNDER_MINUTE), elapsedBucket(0L))
        assertEquals(ElapsedBucket(ElapsedUnit.UNDER_MINUTE), elapsedBucket(59_999L))
        assertEquals(ElapsedBucket(ElapsedUnit.MINUTES, 1), elapsedBucket(60_000L))
        assertEquals(ElapsedBucket(ElapsedUnit.MINUTES, 59), elapsedBucket(59 * 60_000L))
        assertEquals(ElapsedBucket(ElapsedUnit.HOURS, 1), elapsedBucket(60 * 60_000L))
        assertEquals(ElapsedBucket(ElapsedUnit.HOURS, 23), elapsedBucket(23 * 60 * 60_000L))
        assertEquals(ElapsedBucket(ElapsedUnit.DAYS, 1), elapsedBucket(24 * 60 * 60_000L))
        assertEquals(ElapsedBucket(ElapsedUnit.DAYS, 3), elapsedBucket(3 * 24 * 60 * 60_000L))
    }

    @Test
    fun `a backwards clock clamps instead of rendering a negative`() {
        assertEquals(ElapsedBucket(ElapsedUnit.UNDER_MINUTE), elapsedBucket(-500_000L))
    }

    // --- failureReasonOf ---

    @Test
    fun `a missing broadcast row gives the unknown reason`() {
        assertEquals(TxFailureReason.UNKNOWN, failureReasonOf(null))
    }

    @Test
    fun `the null threshold separates dropped from rejected`() {
        assertEquals(TxFailureReason.REJECTED, failureReasonOf(broadcast(nullCount = 2)))
        assertEquals(TxFailureReason.DROPPED, failureReasonOf(broadcast(nullCount = 3)))
        assertEquals(TxFailureReason.DROPPED, failureReasonOf(broadcast(nullCount = 7)))
        assertEquals(3, BROADCAST_NULL_THRESHOLD, "must stay equal to BroadcastWatchdog.NULL_THRESHOLD")
    }
}
