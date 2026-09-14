package com.rjnr.pocketnode.data.gateway.models

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Display formatting for [TransactionRecord], pinned to the strings the JVM
 * `String.format` path produced before the move to commonMain (#455). The app-module
 * TransactionRecordTest covers the same API from the Android side; this suite exists so the
 * rounding contract is also checked on iOS, where tie-breaking used to differ.
 */
class TransactionRecordFormatTest {

    private fun record(
        balanceChange: String = "0x0",
        direction: String = "in",
        confirmations: Int = 10,
        timestamp: Long = 0L,
    ) = TransactionRecord(
        txHash = "0x" + "ab".repeat(32),
        blockNumber = "0x100",
        blockHash = "0x" + "cd".repeat(32),
        timestamp = timestamp,
        balanceChange = balanceChange,
        direction = direction,
        fee = "0x186a0",
        confirmations = confirmations,
    )

    private fun hex(shannons: Long) = "0x" + shannons.toString(16)

    // --- formattedAmount ---

    @Test
    fun roundsHalfUpAtTheTwoDecimalCutoff() {
        // 61.005 CKB and 12.345 CKB: the exact-decimal HALF_UP answers.
        assertEquals("+61.01 CKB", record(hex(6_100_500_000L)).formattedAmount())
        assertEquals("+12.35 CKB", record(hex(1_234_500_000L)).formattedAmount())
    }

    @Test
    fun usesTwoDecimalsAtOrAboveOneCkb() {
        assertEquals("+1.00 CKB", record(hex(100_000_000L)).formattedAmount())
        assertEquals("+100.00 CKB", record(hex(10_000_000_000L)).formattedAmount())
    }

    @Test
    fun usesFourDecimalsBetweenAHundredMicroCkbAndOneCkb() {
        assertEquals("+0.0001 CKB", record(hex(10_000L)).formattedAmount())
        assertEquals("-0.0005 CKB", record(hex(50_000L), direction = "out").formattedAmount())
    }

    @Test
    fun usesEightDecimalsBelowTheFourDecimalCutoff() {
        assertEquals("+0.00000001 CKB", record(hex(1L)).formattedAmount())
        assertEquals("+0.00001000 CKB", record(hex(1_000L)).formattedAmount())
        assertEquals("+0.00000000 CKB", record("0x0").formattedAmount())
    }

    @Test
    fun signsByDirection() {
        val amount = hex(100_000_000_000L)
        assertEquals("+1000.00 CKB", record(amount, direction = "in").formattedAmount())
        assertEquals("-1000.00 CKB", record(amount, direction = "out").formattedAmount())
        assertEquals("1000.00 CKB", record(amount, direction = "self").formattedAmount())
        assertEquals("-1000.00 CKB", record(amount, direction = "dao_deposit").formattedAmount())
        assertEquals("+1000.00 CKB", record(amount, direction = "dao_unlock").formattedAmount())
    }

    @Test
    fun formatsAnUnparseableAmountAsZero() {
        assertEquals("+0.00000000 CKB", record("not-hex").formattedAmount())
    }

    // --- compactConfirmations ---

    @Test
    fun compactsConfirmationsWithHalfUpRounding() {
        assertEquals("999", record(confirmations = 999).compactConfirmations())
        assertEquals("1.0K", record(confirmations = 1_000).compactConfirmations())
        assertEquals("1.1K", record(confirmations = 1_050).compactConfirmations())
        assertEquals("1.3K", record(confirmations = 1_250).compactConfirmations())
        assertEquals("7.4K", record(confirmations = 7_438).compactConfirmations())
        assertEquals("1.5M", record(confirmations = 1_500_000).compactConfirmations())
    }

    // --- getRelativeTimeString: buckets against a fixed clock ---

    @Test
    fun relativeTimeFallsBackWhenThereIsNoTimestamp() {
        assertEquals("Pending", record(timestamp = 0L, confirmations = 0).getRelativeTimeString(NOW))
        assertEquals("Confirmed", record(timestamp = 0L, confirmations = 5).getRelativeTimeString(NOW))
    }

    @Test
    fun relativeTimeBuckets() {
        assertEquals("Just now", relativeTimeAgo(seconds = 59))
        assertEquals("1 min ago", relativeTimeAgo(seconds = 60))
        assertEquals("59 min ago", relativeTimeAgo(minutes = 59))
        assertEquals("1 hr ago", relativeTimeAgo(minutes = 60))
        assertEquals("23 hr ago", relativeTimeAgo(hours = 23))
        assertEquals("Yesterday", relativeTimeAgo(hours = 24))
        assertEquals("2 days ago", relativeTimeAgo(days = 2))
        assertEquals("6 days ago", relativeTimeAgo(days = 6))
        assertEquals("1 weeks ago", relativeTimeAgo(days = 7))
        assertEquals("4 weeks ago", relativeTimeAgo(days = 29))
        assertEquals("1 months ago", relativeTimeAgo(days = 30))
        assertEquals("12 months ago", relativeTimeAgo(days = 364))
        assertEquals("1 years ago", relativeTimeAgo(days = 365))
    }

    private fun relativeTimeAgo(
        seconds: Long = 0,
        minutes: Long = 0,
        hours: Long = 0,
        days: Long = 0,
    ): String {
        val elapsedMillis = ((((days * 24 + hours) * 60 + minutes) * 60) + seconds) * 1000L
        return record(timestamp = NOW - elapsedMillis, confirmations = 1).getRelativeTimeString(NOW)
    }

    private companion object {
        /** Fixed "now": 2026-01-01T00:00:00Z in millis. No platform clock is read. */
        const val NOW = 1_767_225_600_000L
    }
}
