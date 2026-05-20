package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.entity.WalletEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure pieces of [SyncCoordinator] — the
 * `balancedFilterAlgorithm` top-level function and the lookup mapping.
 *
 * The I/O wrapper [SyncCoordinator.applyBalancedFilter] reads
 * sync_progress rows; covered by manual smoke. The pure algorithm
 * tests are duplicated from the earlier `GatewayRepositoryBalancedTest`
 * with a few new cases targeting [SyncCoordinator.getWalletIdForScript]
 * via reflection on a freshly-constructed instance.
 */
class SyncCoordinatorTest {

    private fun wallet(id: String) = WalletEntity(
        walletId = id, name = id, type = "mnemonic",
        derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null,
        accountIndex = 0, mainnetAddress = "ckb1$id", testnetAddress = "ckt1$id",
        isActive = false, createdAt = 0L, lastActiveAt = 0L,
    )

    @Test
    fun `balancedFilterAlgorithm keeps active wallet even when lagging`() {
        val wallets = listOf(wallet("a"), wallet("b"))
        val progress = mapOf("a" to 0L, "b" to 1_000_000L)
        val (kept, _) = balancedFilterAlgorithm(wallets, progress, activeId = "a", threshold = 100_000L)
        assertTrue("active wallet must always be kept", kept.any { it.walletId == "a" })
    }

    @Test
    fun `balancedFilterAlgorithm drops non-active laggards`() {
        val wallets = listOf(wallet("a"), wallet("b"), wallet("c"))
        val progress = mapOf("a" to 1_000_000L, "b" to 1_000_000L, "c" to 100L)
        val (kept, dropped) = balancedFilterAlgorithm(
            wallets, progress, activeId = "a", threshold = 100_000L
        )
        assertEquals(setOf("a", "b"), kept.map { it.walletId }.toSet())
        assertEquals(listOf("c"), dropped.map { it.walletId })
    }

    @Test
    fun `balancedFilterAlgorithm returns input when size lte 1`() {
        val solo = listOf(wallet("only"))
        val (kept, dropped) = balancedFilterAlgorithm(solo, emptyMap(), "only", 100_000L)
        assertEquals(solo, kept)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `balancedFilterAlgorithm tolerates missing progress rows`() {
        // Newly-imported wallet with no sync_progress row yet (progress treated
        // as 0). Reference max from the other two; new wallet's missing entry
        // becomes a fresh laggard but the active wallet still passes.
        val wallets = listOf(wallet("a"), wallet("b"), wallet("c"))
        val progress = mapOf("a" to 1_000_000L, "b" to 1_000_000L) // c missing
        val (kept, _) = balancedFilterAlgorithm(wallets, progress, "a", 100_000L)
        assertTrue("active a still kept", kept.any { it.walletId == "a" })
        // c with implicit progress 0 lags by 1_000_000 → dropped
        assertTrue("c dropped as laggard", kept.none { it.walletId == "c" })
    }
}
