package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.EpochInfo
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #332 DAO windowing recovery: deposits discovered by a live scan are
 * persisted to `dao_cells`, and cached deposits older than the script's
 * sync window are merged back into the list (flagged) instead of silently
 * vanishing when the user narrows the window.
 */
class DaoCellMappingTest {

    private fun deposit(
        block: Long = 8_000_000L,
        status: DaoCellStatus = DaoCellStatus.DEPOSITED,
    ) = DaoDeposit(
        outPoint = OutPoint(txHash = "0x" + "ab".repeat(32), index = "0x0"),
        capacity = 500_00000000L,
        status = status,
        depositBlockNumber = block,
        depositBlockHash = "0x" + "cd".repeat(32),
        depositEpoch = EpochInfo(number = 7000, index = 1, length = 1800),
        compensation = 12_00000000L,
        depositTimestamp = 1_700_000_000_000L,
    )

    @Test
    fun `round-trips through the entity`() {
        val original = deposit()
        val entity = original.toDaoCellEntity(network = "MAINNET", walletId = "w1", nowMs = 42L)
        assertEquals("MAINNET", entity.network)
        assertEquals("w1", entity.walletId)
        assertEquals("DEPOSITED", entity.status)
        assertEquals(original.capacity, entity.capacity)
        assertEquals(original.depositBlockNumber, entity.depositBlockNumber)

        val back = entity.toOutsideWindowDeposit()
        assertEquals(original.outPoint, back.outPoint)
        assertEquals(original.capacity, back.capacity)
        assertEquals(original.status, back.status)
        assertEquals(original.depositBlockNumber, back.depositBlockNumber)
        assertEquals(original.depositEpoch, back.depositEpoch)
        assertTrue("merged-from-cache deposit must be flagged", back.outsideSyncWindow)
    }

    @Test
    fun `unknown status string degrades to DEPOSITED not crash`() {
        val entity = deposit().toDaoCellEntity("MAINNET", "w1", 0L)
            .copy(status = "SOMETHING_FUTURE")
        assertEquals(DaoCellStatus.DEPOSITED, entity.toOutsideWindowDeposit().status)
    }

    @Test
    fun `malformed epoch hex degrades to null`() {
        val entity = deposit().toDaoCellEntity("MAINNET", "w1", 0L)
            .copy(depositEpochHex = "0xZZ")
        assertNull(entity.toOutsideWindowDeposit().depositEpoch)
    }
}
