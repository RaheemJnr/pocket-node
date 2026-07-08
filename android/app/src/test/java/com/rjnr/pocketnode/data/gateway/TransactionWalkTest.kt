package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellInput
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniTransactionView
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the #386/#388 pagination-boundary bug class, verified
 * independently by a Nervos tester (yanli): "the actual trigger is the fixed
 * first-page limit of 100 cells/transactions. A 200-cell mock dataset already
 * reproduces the same failure mode deterministically." The failure was
 * computing balance / spent-set / transaction net from a SINGLE page, so a
 * transaction whose interactions straddle the 100-item boundary was scored
 * from partial data — wrong amount, even wrong send/receive direction.
 *
 * These lock in the fix: the walk collects every page before any computation,
 * and the net/spent computations are correct over the complete set.
 */
class TransactionWalkTest {

    private fun view(hash: String) = JniTransactionView(
        hash = hash, version = "0x0", cellDeps = emptyList(), headerDeps = emptyList(),
        inputs = listOf(CellInput(previousOutput = OutPoint(hash, "0x0"))),
        outputs = emptyList(), outputsData = emptyList(), witnesses = emptyList(),
    )

    private fun interaction(txHash: String, ioType: String, capacityShannons: Long) = JniTxWithCell(
        transaction = view(txHash),
        blockNumber = "0x1", txIndex = "0x0", ioIndex = "0x0",
        ioType = ioType, ioCapacity = "0x${capacityShannons.toString(16)}",
    )

    private fun page(items: List<JniTxWithCell>, cursor: String?) = JniPagination(items, cursor)

    // --- walkAllPages: the walk must not stop at the first page ---

    @Test
    fun `walk collects every page across the 100-item boundary`() = runTest {
        // 200 interactions split into two full pages, exactly yanli's repro.
        val p1 = (0 until 100).map { interaction("0x${it}", "output", 1) }
        val p2 = (100 until 200).map { interaction("0x${it}", "output", 1) }
        var call = 0
        val result = walkAllPages(pageLimit = 100, maxPages = 200) { cursor ->
            when (call++) {
                0 -> page(p1, "0xcursor").also { assertEquals(null, cursor) }
                1 -> page(p2, null).also { assertEquals("0xcursor", cursor) }
                else -> null
            }
        }
        assertEquals(200, result.items.size)
        assertEquals(2, result.pagesWalked)
        assertFalse(result.hitCap)
    }

    @Test
    fun `walk stops early on a short page`() = runTest {
        val result = walkAllPages(pageLimit = 100, maxPages = 200) { _ ->
            page((0 until 40).map { interaction("0x$it", "output", 1) }, "0xstillhascursor")
        }
        assertEquals(40, result.items.size)
        assertEquals(1, result.pagesWalked) // size < limit ⇒ last page
    }

    @Test
    fun `walk stops at the runaway cap and flags it`() = runTest {
        // Never-ending cursor: the cap must bound it and report the truncation.
        val result = walkAllPages(pageLimit = 100, maxPages = 3) { _ ->
            page((0 until 100).map { interaction("0x$it", "output", 1) }, "0xnever-empty")
        }
        assertEquals(3, result.pagesWalked)
        assertTrue(result.hitCap)
    }

    @Test
    fun `walk stops on a null page (JNI failure)`() = runTest {
        val result = walkAllPages<JniTxWithCell>(pageLimit = 100, maxPages = 200) { _ -> null }
        assertTrue(result.items.isEmpty())
    }

    // --- netShannonsByTx: correct over the COMPLETE set ---

    @Test
    fun `boundary-straddling transaction nets correctly from the full walk`() {
        // Tx T's input lands on page 1, its change output on page 2. Scored
        // from either page alone it looks like a +95k receive or a -100k send;
        // only the complete set gives the true -5k net (amount sent + fee).
        val inputOnPage1 = interaction("0xT", "input", 100_000)
        val changeOnPage2 = interaction("0xT", "output", 95_000)
        val net = netShannonsByTx(listOf(inputOnPage1, changeOnPage2))
        assertEquals(-5_000L, net["0xT"])
    }

    @Test
    fun `net groups independent transactions separately`() {
        val net = netShannonsByTx(
            listOf(
                interaction("0xA", "output", 500),   // receive
                interaction("0xB", "input", 300),     // send
                interaction("0xB", "output", 100),    // its change
            )
        )
        assertEquals(500L, net["0xA"])
        assertEquals(-200L, net["0xB"])
    }

    // --- spentOutpointsOf: collects from the full set ---

    @Test
    fun `spent outpoints are collected across both pages`() {
        val all = listOf(
            interaction("0xtx1", "input", 1),
            interaction("0xtx2", "input", 1),
        )
        val spent = spentOutpointsOf(all)
        assertTrue(spent.contains("0xtx1:0x0"))
        assertTrue(spent.contains("0xtx2:0x0"))
        assertEquals(2, spent.size)
    }
}
