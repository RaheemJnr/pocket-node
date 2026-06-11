package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Malformed hex from the node must degrade to 0, not crash the balance
 * flow collectors with an uncaught NumberFormatException (#321).
 */
class CapacityParseTest {

    private val lock = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = "0x0011223344556677889900112233445566778899"
    )

    @Test
    fun `Cell capacityAsLong parses valid hex`() {
        val cell = Cell(
            outPoint = OutPoint("0x" + "ab".repeat(32), "0x0"),
            capacity = "0x174876e800", // 1000 CKB
            blockNumber = "0x1",
            lock = lock
        )
        assertEquals(100_000_000_000L, cell.capacityAsLong())
    }

    @Test
    fun `Cell capacityAsLong returns 0 on malformed hex`() {
        val cell = Cell(
            outPoint = OutPoint("0x" + "ab".repeat(32), "0x0"),
            capacity = "0xZZNOTHEX",
            blockNumber = "0x1",
            lock = lock
        )
        assertEquals(0L, cell.capacityAsLong())
    }

    @Test
    fun `BalanceResponse capacityAsLong parses valid hex`() {
        val balance = BalanceResponse(
            address = "ckt1...",
            capacity = "0x174876e800",
            capacityCkb = "1000",
            asOfBlock = "0x1"
        )
        assertEquals(100_000_000_000L, balance.capacityAsLong())
    }

    @Test
    fun `BalanceResponse capacityAsLong returns 0 on malformed hex`() {
        val balance = BalanceResponse(
            address = "ckt1...",
            capacity = "garbage",
            capacityCkb = "",
            asOfBlock = "0x1"
        )
        assertEquals(0L, balance.capacityAsLong())
    }
}
