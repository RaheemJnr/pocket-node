package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.storage.WalletRecord
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the production BALANCED filter algorithm.
 *
 * Calls the top-level `balancedFilterAlgorithm` directly — exercises
 * production code, not a duplicate. The DAO read + logging wrapper
 * (SyncCoordinator.applyBalancedFilter) is covered by manual smoke
 * since it requires a full set of storage seams to construct.
 */
class GatewayRepositoryBalancedTest {

    private fun wallet(id: String) = WalletRecord(
        walletId = id,
        mainnetAddress = "ckb1$id",
        testnetAddress = "ckt1$id",
        lastActiveAt = 0L,
    )

    private fun filterKept(
        wallets: List<WalletRecord>,
        progress: Map<String, Long>,
        activeId: String,
        threshold: Long = 100_000L
    ): List<WalletRecord> = balancedFilterAlgorithm(wallets, progress, activeId, threshold).first

    @Test
    fun `single wallet returns input unchanged`() {
        val w = listOf(wallet("a"))
        assertEquals(w, filterKept(w, emptyMap(), activeId = "a"))
    }

    @Test
    fun `all wallets within threshold all kept`() {
        val r = filterKept(
            listOf(wallet("a"), wallet("b"), wallet("c")),
            mapOf("a" to 1_000_000L, "b" to 950_000L, "c" to 900_001L),
            activeId = "a"
        )
        assertEquals(3, r.size)
    }

    @Test
    fun `laggard dropped when active is leader`() {
        val r = filterKept(
            listOf(wallet("a"), wallet("b")),
            mapOf("a" to 1_000_000L, "b" to 500_000L),  // 500k behind
            activeId = "a"
        )
        assertEquals(listOf("a"), r.map { it.walletId })
    }

    @Test
    fun `active wallet kept even when itself is the laggard`() {
        val r = filterKept(
            listOf(wallet("a"), wallet("b")),
            mapOf("a" to 1_000_000L, "b" to 500_000L),
            activeId = "b"
        )
        assertEquals(setOf("a", "b"), r.map { it.walletId }.toSet())
    }

    @Test
    fun `multiple laggards dropped active kept`() {
        val r = filterKept(
            listOf(wallet("a"), wallet("b"), wallet("c")),
            mapOf("a" to 1_000_000L, "b" to 500_000L, "c" to 400_000L),
            activeId = "a"
        )
        assertEquals(listOf("a"), r.map { it.walletId })
    }

    @Test
    fun `no progress rows present all wallets pass`() {
        // Empty progress map → maxProgress=0, every lag=0 → all pass
        val r = filterKept(
            listOf(wallet("a"), wallet("b"), wallet("c")),
            emptyMap(),
            activeId = "a"
        )
        assertEquals(3, r.size)
    }

    @Test
    fun `boundary lag equals threshold kept`() {
        val r = filterKept(
            listOf(wallet("a"), wallet("b")),
            mapOf("a" to 200_000L, "b" to 100_000L),  // exactly 100k behind
            activeId = "a"
        )
        assertEquals(2, r.size)
    }

    @Test
    fun `boundary lag exceeds threshold by one dropped`() {
        val r = filterKept(
            listOf(wallet("a"), wallet("b")),
            mapOf("a" to 200_000L, "b" to 99_999L),   // 100_001 behind
            activeId = "a"
        )
        assertEquals(listOf("a"), r.map { it.walletId })
    }

    @Test
    fun `algorithm is pure so calling twice with same state returns same set`() {
        val wallets = listOf(wallet("a"), wallet("b"))
        val progress = mapOf("a" to 1_000_000L, "b" to 950_000L)

        val first = filterKept(wallets, progress, activeId = "a")
        val second = filterKept(wallets, progress, activeId = "a")

        assertEquals(first.map { it.walletId }, second.map { it.walletId })
    }

    @Test
    fun `dropped list contains laggards in original order`() {
        val (kept, dropped) = balancedFilterAlgorithm(
            wallets = listOf(wallet("a"), wallet("b"), wallet("c")),
            progressByWalletId = mapOf("a" to 1_000_000L, "b" to 500_000L, "c" to 600_000L),
            activeId = "a",
            threshold = 100_000L
        )
        assertEquals(listOf("a"), kept.map { it.walletId })
        assertEquals(listOf("b", "c"), dropped.map { it.walletId })
    }
}
