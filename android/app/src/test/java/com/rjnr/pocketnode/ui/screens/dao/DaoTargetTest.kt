package com.rjnr.pocketnode.ui.screens.dao

import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #524 N5-1: saved DAO dialogs resolve only to a deposit their action applies to. */
class DaoTargetTest {

    private fun deposit(tx: String, status: DaoCellStatus) = DaoDeposit(
        outPoint = OutPoint(txHash = tx, index = "0x0"),
        capacity = 10_200_000_000L,
        status = status,
        depositBlockNumber = 1L,
    )

    private val deposited = deposit("0xaa", DaoCellStatus.DEPOSITED)
    private val unlockable = deposit("0xbb", DaoCellStatus.UNLOCKABLE)
    private val locked = deposit("0xcc", DaoCellStatus.LOCKED)
    private val active = listOf(deposited, unlockable, locked)

    @Test
    fun `withdraw resolves only a DEPOSITED deposit`() {
        assertEquals(deposited, resolveDaoTarget(active, deposited.daoTargetKey(), DaoCellStatus.DEPOSITED))
        assertNull(resolveDaoTarget(active, unlockable.daoTargetKey(), DaoCellStatus.DEPOSITED))
    }

    @Test
    fun `unlock resolves only an UNLOCKABLE deposit`() {
        assertEquals(unlockable, resolveDaoTarget(active, unlockable.daoTargetKey(), DaoCellStatus.UNLOCKABLE))
        assertNull(resolveDaoTarget(active, locked.daoTargetKey(), DaoCellStatus.UNLOCKABLE))
    }

    @Test
    fun `a deposit that moved on or left the active list closes the dialog`() {
        val nowWithdrawing = deposit("0xaa", DaoCellStatus.WITHDRAWING)
        assertNull(resolveDaoTarget(listOf(nowWithdrawing), deposited.daoTargetKey(), DaoCellStatus.DEPOSITED))
        assertNull(resolveDaoTarget(emptyList(), deposited.daoTargetKey(), DaoCellStatus.DEPOSITED))
        assertNull(resolveDaoTarget(active, null, DaoCellStatus.DEPOSITED))
    }
}
