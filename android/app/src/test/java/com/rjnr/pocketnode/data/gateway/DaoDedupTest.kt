package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #357: after a phase-1 withdraw commits, the light client briefly lists both
 * the new withdrawing cell AND the now-spent deposit cell (its spent-outpoint
 * filter lags get_cells), so the deposit showed a duplicate "Confirming…"
 * card. The withdrawing cell carries the outpoints its tx consumed; drop any
 * DEPOSITED entry that one of them consumed.
 */
class DaoDedupTest {

    private fun op(tx: String, idx: String = "0x0") = OutPoint(txHash = tx, index = idx)

    private fun deposit(
        tx: String,
        status: DaoCellStatus,
        consumed: List<OutPoint> = emptyList(),
    ) = DaoDeposit(
        outPoint = op(tx),
        capacity = 102_00000000L,
        status = status,
        depositBlockNumber = 100,
        consumedDepositOutPoints = consumed,
    )

    @Test
    fun `drops the deposit consumed by a withdrawing cell`() {
        val depositD = deposit("0xdeposit", DaoCellStatus.DEPOSITED)
        val withdrawingW = deposit("0xwithdraw", DaoCellStatus.LOCKED, consumed = listOf(op("0xdeposit")))
        val out = dedupeWithdrawnDeposits(listOf(depositD, withdrawingW))
        assertEquals(listOf(withdrawingW), out)
    }

    @Test
    fun `keeps an unrelated deposit`() {
        val other = deposit("0xother", DaoCellStatus.DEPOSITED)
        val withdrawingW = deposit("0xwithdraw", DaoCellStatus.LOCKED, consumed = listOf(op("0xdeposit")))
        val out = dedupeWithdrawnDeposits(listOf(other, withdrawingW))
        assertTrue(other in out && withdrawingW in out)
        assertEquals(2, out.size)
    }

    @Test
    fun `index must match, not just tx hash`() {
        // A deposit at index 1 is NOT the one consumed at index 0.
        val depositD1 = deposit("0xdeposit", DaoCellStatus.DEPOSITED).copy(outPoint = op("0xdeposit", "0x1"))
        val withdrawingW = deposit("0xwithdraw", DaoCellStatus.LOCKED, consumed = listOf(op("0xdeposit", "0x0")))
        val out = dedupeWithdrawnDeposits(listOf(depositD1, withdrawingW))
        assertEquals(2, out.size)
    }

    @Test
    fun `no withdrawing cells leaves the list unchanged`() {
        val a = deposit("0xa", DaoCellStatus.DEPOSITED)
        val b = deposit("0xb", DaoCellStatus.DEPOSITED)
        assertEquals(listOf(a, b), dedupeWithdrawnDeposits(listOf(a, b)))
    }

    @Test
    fun `only DEPOSITED entries are dropped, not a withdrawing one`() {
        // Defensive: a consumed outpoint should never match a non-DEPOSITED
        // entry, but if it did we must not drop the withdrawing position.
        val w1 = deposit("0xw1", DaoCellStatus.LOCKED, consumed = listOf(op("0xw2")))
        val w2 = deposit("0xw2", DaoCellStatus.UNLOCKABLE)
        val out = dedupeWithdrawnDeposits(listOf(w1, w2))
        assertEquals(2, out.size)
    }
}
