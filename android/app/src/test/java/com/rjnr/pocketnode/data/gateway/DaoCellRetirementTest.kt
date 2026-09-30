package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-row decision behind the #332 / #434 / #529 merge: what happens to a
 * cached DAO row the live scan no longer returns.
 */
class DaoCellRetirementTest {

    private val windowStart = 10_000L

    private fun fate(
        status: DaoCellStatus = DaoCellStatus.UNLOCKABLE,
        depositBlockNumber: Long = 500L,
        consumedByLiveWithdraw: Boolean = false,
        unlockState: DaoUnlockMarkerState? = null,
    ) = resolveCachedDaoCell(
        cachedStatus = status.name,
        depositBlockNumber = depositBlockNumber,
        windowStart = windowStart,
        consumedByLiveWithdraw = consumedByLiveWithdraw,
        unlockState = unlockState,
    )

    @Test
    fun `a confirmed unlock retires the row even though its deposit predates the window`() {
        // The withdrawing row carries the ORIGINAL deposit block, so it always
        // looks "before the window" once the sync head has moved past it. The
        // confirmed unlock has to win, or the claimed position renders forever.
        assertEquals(
            CachedDaoCellFate.RETIRE,
            fate(unlockState = DaoUnlockMarkerState.CONFIRMED),
        )
    }

    @Test
    fun `an unlock still in flight holds the row rather than retiring it`() {
        assertEquals(
            CachedDaoCellFate.UNLOCK_IN_FLIGHT,
            fate(unlockState = DaoUnlockMarkerState.IN_FLIGHT),
        )
    }

    @Test
    fun `a failed unlock is as if none had been attempted`() {
        // Nothing was spent, so the row is classified by the window rules and
        // stays visible for a retry.
        assertEquals(
            CachedDaoCellFate.OUTSIDE_WINDOW,
            fate(unlockState = DaoUnlockMarkerState.FAILED),
        )
    }

    @Test
    fun `an old deposit with no spend signal stays visible as outside-window`() {
        assertEquals(CachedDaoCellFate.OUTSIDE_WINDOW, fate(status = DaoCellStatus.DEPOSITED))
    }

    @Test
    fun `a cell consumed by a live withdraw is retired`() {
        assertEquals(
            CachedDaoCellFate.RETIRE,
            fate(status = DaoCellStatus.DEPOSITED, consumedByLiveWithdraw = true),
        )
    }

    @Test
    fun `a cell inside the window that vanished was spent and is retired`() {
        assertEquals(
            CachedDaoCellFate.RETIRE,
            fate(status = DaoCellStatus.DEPOSITED, depositBlockNumber = windowStart + 5),
        )
    }

    @Test
    fun `a DEPOSITING row is left alone`() {
        assertEquals(
            CachedDaoCellFate.IGNORE,
            fate(status = DaoCellStatus.DEPOSITING, depositBlockNumber = 0L),
        )
    }

    @Test
    fun `a DEPOSITING row is left alone even with a spend signal`() {
        assertEquals(
            CachedDaoCellFate.IGNORE,
            fate(
                status = DaoCellStatus.DEPOSITING,
                depositBlockNumber = 0L,
                unlockState = DaoUnlockMarkerState.CONFIRMED,
            ),
        )
    }

    @Test
    fun `an unknown window start cannot classify anything as outside-window`() {
        assertEquals(
            CachedDaoCellFate.RETIRE,
            resolveCachedDaoCell(
                cachedStatus = DaoCellStatus.DEPOSITED.name,
                depositBlockNumber = 500L,
                windowStart = 0L,
                consumedByLiveWithdraw = false,
                unlockState = null,
            ),
        )
    }

    // --- marker state machine (#529) ---

    private val unknown = DaoUnlockChainVerdict.UNKNOWN

    @Test
    fun `the transaction cache decides while it has an answer`() {
        assertEquals(
            DaoUnlockMarkerState.CONFIRMED,
            daoUnlockMarkerState("CONFIRMED", markerAgeMs = 0L, chainVerdict = unknown),
        )
        assertEquals(
            DaoUnlockMarkerState.FAILED,
            daoUnlockMarkerState("FAILED", markerAgeMs = 0L, chainVerdict = unknown),
        )
    }

    @Test
    fun `an unscored marker inside the grace period is still in flight`() {
        assertEquals(
            DaoUnlockMarkerState.IN_FLIGHT,
            daoUnlockMarkerState(null, markerAgeMs = 60_000L, chainVerdict = unknown),
        )
        assertEquals(
            "the chain is not even consulted yet, so it cannot promote it",
            DaoUnlockMarkerState.IN_FLIGHT,
            daoUnlockMarkerState(
                "PENDING",
                markerAgeMs = 60_000L,
                chainVerdict = DaoUnlockChainVerdict.COMMITTED,
            ),
        )
    }

    @Test
    fun `past the grace period the chain decides`() {
        val aged = DAO_UNLOCK_MARKER_GRACE_MS + 1
        assertEquals(
            DaoUnlockMarkerState.CONFIRMED,
            daoUnlockMarkerState("PENDING", aged, DaoUnlockChainVerdict.COMMITTED),
        )
        assertEquals(
            DaoUnlockMarkerState.FAILED,
            daoUnlockMarkerState("PENDING", aged, DaoUnlockChainVerdict.REJECTED),
        )
        assertEquals(
            "still in the pool, so it may yet land",
            DaoUnlockMarkerState.IN_FLIGHT,
            daoUnlockMarkerState("PENDING", aged, DaoUnlockChainVerdict.IN_POOL),
        )
    }

    @Test
    fun `no answer from the chain never counts as a spend`() {
        val aged = DAO_UNLOCK_MARKER_GRACE_MS + 1
        assertEquals(
            "the node was unreachable, so hand the position back rather than hide it",
            DaoUnlockMarkerState.FAILED,
            daoUnlockMarkerState("PENDING", aged, unknown),
        )
        assertEquals(
            "no local row at all says nothing about the chain",
            DaoUnlockMarkerState.FAILED,
            daoUnlockMarkerState(null, aged, unknown),
        )
    }

    @Test
    fun `an in-pool answer is not believed forever`() {
        assertEquals(
            "a day in the pool is a stale local mempool entry, not a pending spend",
            DaoUnlockMarkerState.FAILED,
            daoUnlockMarkerState(
                "PENDING",
                markerAgeMs = DAO_UNLOCK_MARKER_TTL_MS + 1,
                chainVerdict = DaoUnlockChainVerdict.IN_POOL,
            ),
        )
    }

    // --- marker resolution (#529) ---

    @Test
    fun `nothing is retired while the cell is still listed`() {
        // The write-through rewrites that row from the live scan every
        // refresh, so a retirement there would be undone a moment later.
        assertEquals(
            PendingUnlockResolution.OVERLAY,
            resolvePendingUnlock(true, DaoUnlockMarkerState.CONFIRMED),
        )
        assertEquals(
            PendingUnlockResolution.OVERLAY,
            resolvePendingUnlock(true, DaoUnlockMarkerState.IN_FLIGHT),
        )
    }

    @Test
    fun `a gone cell with a confirmed unlock is retired`() {
        assertEquals(
            PendingUnlockResolution.RETIRE,
            resolvePendingUnlock(false, DaoUnlockMarkerState.CONFIRMED),
        )
    }

    @Test
    fun `a gone cell with an unresolved unlock keeps its marker`() {
        assertEquals(
            PendingUnlockResolution.OVERLAY,
            resolvePendingUnlock(false, DaoUnlockMarkerState.IN_FLIGHT),
        )
    }

    @Test
    fun `a failed unlock restores the position either way`() {
        assertEquals(
            PendingUnlockResolution.RESTORE,
            resolvePendingUnlock(true, DaoUnlockMarkerState.FAILED),
        )
        assertEquals(
            PendingUnlockResolution.RESTORE,
            resolvePendingUnlock(false, DaoUnlockMarkerState.FAILED),
        )
    }
}
