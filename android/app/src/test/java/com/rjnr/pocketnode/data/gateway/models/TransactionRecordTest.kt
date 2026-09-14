package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.*
import org.junit.Test

class TransactionRecordTest {

    private fun makeRecord(
        balanceChange: String = "0x2540be400",
        direction: String = "in",
        confirmations: Int = 10,
        timestamp: Long = System.currentTimeMillis() - 3600_000
    ) = TransactionRecord(
        txHash = "0x" + "ab".repeat(32),
        blockNumber = "0x100",
        blockHash = "0x" + "cd".repeat(32),
        timestamp = timestamp,
        balanceChange = balanceChange,
        direction = direction,
        fee = "0x186a0",
        confirmations = confirmations
    )

    // --- balanceChangeAsCkb ---

    @Test
    fun `balanceChangeAsCkb converts 100 CKB correctly`() {
        val record = makeRecord(balanceChange = "0x2540be400")
        assertEquals(100.0, record.balanceChangeAsCkb(), 0.001)
    }

    @Test
    fun `balanceChangeAsCkb handles zero`() {
        val record = makeRecord(balanceChange = "0x0")
        assertEquals(0.0, record.balanceChangeAsCkb(), 0.001)
    }

    // --- formattedAmount ---

    @Test
    fun `formattedAmount shows plus for incoming`() {
        val record = makeRecord(direction = "in", balanceChange = "0x2540be400")
        assertTrue(record.formattedAmount().startsWith("+"))
        assertTrue(record.formattedAmount().endsWith("CKB"))
    }

    @Test
    fun `formattedAmount shows minus for outgoing`() {
        val record = makeRecord(direction = "out")
        assertTrue(record.formattedAmount().startsWith("-"))
    }

    @Test
    fun `formattedAmount no sign for self`() {
        val record = makeRecord(direction = "self")
        assertFalse(record.formattedAmount().startsWith("+"))
        assertFalse(record.formattedAmount().startsWith("-"))
    }

    // --- compactConfirmations ---

    @Test
    fun `compactConfirmations shows raw number below 1000`() {
        assertEquals("999", makeRecord(confirmations = 999).compactConfirmations())
    }

    @Test
    fun `compactConfirmations shows K for thousands`() {
        assertEquals("7.4K", makeRecord(confirmations = 7438).compactConfirmations())
    }

    @Test
    fun `compactConfirmations shows M for millions`() {
        assertEquals("1.5M", makeRecord(confirmations = 1_500_000).compactConfirmations())
    }

    // --- direction checks ---

    @Test
    fun `isIncoming returns true for in`() {
        assertTrue(makeRecord(direction = "in").isIncoming())
    }

    @Test
    fun `isOutgoing returns true for out`() {
        assertTrue(makeRecord(direction = "out").isOutgoing())
    }

    @Test
    fun `isSelfTransfer returns true for self`() {
        assertTrue(makeRecord(direction = "self").isSelfTransfer())
    }

    // --- confirmation status ---

    @Test
    fun `isConfirmed true when confirmations greater than 0`() {
        assertTrue(makeRecord(confirmations = 1).isConfirmed())
    }

    @Test
    fun `isPending true when confirmations is 0`() {
        assertTrue(makeRecord(confirmations = 0).isPending())
    }

    // --- shortTxHash ---

    @Test
    fun `shortTxHash truncates long hash`() {
        val record = makeRecord()
        val short = record.shortTxHash()
        assertTrue(short.contains("..."))
        assertTrue(short.length < record.txHash.length)
    }

    @Test
    fun `shorterTxHash is even shorter`() {
        val record = makeRecord()
        assertTrue(record.shorterTxHash().length < record.shortTxHash().length)
    }

    // --- getRelativeTimeString ---

    @Test
    fun `getRelativeTimeString returns Pending for zero timestamp zero confirmations`() {
        val record = makeRecord(timestamp = 0L, confirmations = 0)
        assertEquals("Pending", record.getRelativeTimeString(System.currentTimeMillis()))
    }

    @Test
    fun `getRelativeTimeString returns Confirmed for zero timestamp with confirmations`() {
        val record = makeRecord(timestamp = 0L, confirmations = 5)
        assertEquals("Confirmed", record.getRelativeTimeString(System.currentTimeMillis()))
    }

    @Test
    fun `getRelativeTimeString returns Just now for recent timestamp`() {
        val now = System.currentTimeMillis()
        val record = makeRecord(timestamp = now - 5_000)
        assertEquals("Just now", record.getRelativeTimeString(now))
    }
}
