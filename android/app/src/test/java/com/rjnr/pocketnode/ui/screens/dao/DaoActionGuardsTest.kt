package com.rjnr.pocketnode.ui.screens.dao

import com.rjnr.pocketnode.data.gateway.models.DaoAction
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #529: when the card's action button is live. The chain-visible status lags
 * a broadcast by minutes, so the only thing that can close the button in time
 * is what this app knows it already started.
 */
class DaoActionGuardsTest {

    private val outPoint = OutPoint("0x" + "ab".repeat(32), "0x0")
    private val otherOutPoint = OutPoint("0x" + "cd".repeat(32), "0x0")

    private fun deposit(status: DaoCellStatus = DaoCellStatus.UNLOCKABLE) = DaoDeposit(
        outPoint = outPoint,
        capacity = 20_000_000_000L,
        status = status,
        depositBlockNumber = 500L,
    )

    @Test
    fun `an unlockable deposit with nothing in flight is actionable`() {
        assertTrue(daoActionEnabled(deposit(), pendingAction = null))
    }

    @Test
    fun `the button closes while this outpoint is unlocking`() {
        assertFalse(daoActionEnabled(deposit(), DaoAction.Unlocking(outPoint)))
    }

    @Test
    fun `the button closes while this outpoint is withdrawing`() {
        assertFalse(daoActionEnabled(deposit(DaoCellStatus.DEPOSITED), DaoAction.Withdrawing(outPoint)))
    }

    @Test
    fun `another position's pending action does not close this button`() {
        assertTrue(daoActionEnabled(deposit(), DaoAction.Unlocking(otherOutPoint)))
    }

    @Test
    fun `a deposit in flight elsewhere does not close this button`() {
        assertTrue(daoActionEnabled(deposit(), DaoAction.Depositing(10_200_000_000L)))
    }

    @Test
    fun `the overlaid UNLOCKING status closes the button after a relaunch`() {
        // The in-memory pending action is gone after process death; the
        // persisted marker's overlay is what has to hold the button shut.
        assertFalse(daoActionEnabled(deposit(DaoCellStatus.UNLOCKING), pendingAction = null))
    }

    @Test
    fun `the overlaid WITHDRAWING status closes the button after a relaunch`() {
        assertFalse(daoActionEnabled(deposit(DaoCellStatus.WITHDRAWING), pendingAction = null))
    }

    @Test
    fun `daoActionTargets ignores a deposit action, which has no outpoint`() {
        assertFalse(daoActionTargets(DaoAction.Depositing(1L), outPoint))
        assertTrue(daoActionTargets(DaoAction.Unlocking(outPoint), outPoint))
        assertTrue(daoActionTargets(DaoAction.Withdrawing(outPoint), outPoint))
        assertFalse(daoActionTargets(null, outPoint))
    }
}
