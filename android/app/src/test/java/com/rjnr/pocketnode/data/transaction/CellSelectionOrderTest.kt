package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cell selection order (learned from Neuron's cells.ts, which sorts ascending
 * and stops only when change is viable). Pocket Node selected LARGEST-first,
 * which keeps transactions small but never spends dust — so a frequently
 * funded wallet (mining payouts, many receives) fragments until a send needs
 * 100+ inputs and tips over the fee/size edge (Alex, Telegram 2026-07).
 *
 * Smallest-first sweeps dust into every send; the change-viability stop keeps
 * that from stranding the remainder below the 61 CKB minimum (which would be
 * refused as dust change — a regression a naive smallest-first would cause).
 */
@RunWith(RobolectricTestRunner::class)
class CellSelectionOrderTest {

    private val builder = TransactionBuilder(NetworkValidator())
    private val ckb = 100_000_000L
    private val minChange = 61 * ckb

    private fun cell(capacityCkb: Long, tag: String) = Cell(
        outPoint = OutPoint(txHash = "0x" + tag.repeat(64).take(64), index = "0x0"),
        capacity = "0x${(capacityCkb * ckb).toString(16)}",
        blockNumber = "0x100",
        lock = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "ab".repeat(20)),
        type = null,
        data = "0x",
    )

    private fun caps(cells: List<Cell>) =
        cells.map { it.capacity.removePrefix("0x").toLong(16) / ckb }

    @Test
    fun `dust is consumed before large cells`() {
        // Enough dust to fund the send AND leave viable change, so the big
        // cell is never touched — the opposite of largest-first.
        val cells = listOf(cell(200_000, "a"), cell(61, "b"), cell(62, "c"), cell(63, "d"), cell(64, "e"))
        val (selected, _) = builder.selectCells(cells, requiredCapacity = 100 * ckb, minChange = minChange)
        assertTrue("big cell must not be spent", caps(selected).none { it == 200_000L })
        assertTrue("dust is swept in", caps(selected).containsAll(listOf(61L, 62L, 63L)))
    }

    @Test
    fun `selection stops once change is viable, not the instant it is covered`() {
        // 61+62 = 123 covers 100 but leaves 23 CKB change (dust); must add one
        // more to lift change over the 61 CKB minimum, then stop.
        val cells = listOf(cell(61, "a"), cell(62, "b"), cell(63, "c"), cell(64, "d"))
        val (selected, total) = builder.selectCells(cells, requiredCapacity = 100 * ckb, minChange = minChange)
        assertEquals(listOf(61L, 62L, 63L), caps(selected).sorted())
        assertTrue("change must clear the min-capacity floor", total - 100 * ckb >= minChange)
    }

    @Test
    fun `keeps selecting to escape the dust-change zone when a rescue cell exists`() {
        // 61+62+63 = 186 covers 150 but change is 36 (dust); the 100 cell is
        // pulled in to make change viable rather than refusing the send.
        val cells = listOf(cell(61, "a"), cell(62, "b"), cell(63, "c"), cell(100, "d"))
        val (selected, total) = builder.selectCells(cells, requiredCapacity = 150 * ckb, minChange = minChange)
        assertTrue("rescue cell included", caps(selected).contains(100L))
        assertTrue(total - 150 * ckb >= minChange)
    }

    @Test
    fun `normal wallet with one big cell selects just it`() {
        val cells = listOf(cell(10_000, "a"))
        val (selected, _) = builder.selectCells(cells, requiredCapacity = 100 * ckb, minChange = minChange)
        assertEquals(listOf(10_000L), caps(selected))
    }

    @Test
    fun `large send reaches into big cells after exhausting dust`() {
        val cells = listOf(cell(200_000, "a"), cell(61, "b"), cell(62, "c"))
        val (selected, total) = builder.selectCells(cells, requiredCapacity = 1_000 * ckb, minChange = minChange)
        assertTrue("must include the big cell", caps(selected).any { it == 200_000L })
        assertTrue("dust swept in first", caps(selected).containsAll(listOf(61L, 62L)))
        assertTrue(total >= 1_000 * ckb)
    }

    @Test
    fun `exhausting all cells returns them even if change would be dust`() {
        // No rescue cell — selection returns everything and buildTransfer's own
        // dust-change guard decides. Selection must not loop forever.
        val cells = listOf(cell(61, "a"), cell(62, "b"), cell(63, "c"))
        val (selected, total) = builder.selectCells(cells, requiredCapacity = 150 * ckb, minChange = minChange)
        assertEquals(3, selected.size)
        assertEquals(186 * ckb, total)
    }

    @Test
    fun `typed cells are never selected`() {
        val typed = cell(500, "e").copy(
            type = Script("0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e", "type", "0x")
        )
        val (selected, _) = builder.selectCells(listOf(typed, cell(61, "f"), cell(62, "g"), cell(63, "h")), 100 * ckb, minChange)
        assertTrue(caps(selected).none { it == 500L })
    }
}
