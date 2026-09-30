package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear raw prefs so migration guard is gone and each test starts fresh
        rawPrefs().edit().clear().commit()
    }

    private fun rawPrefs() = context.getSharedPreferences("ckb_wallet_prefs", Context.MODE_PRIVATE)

    private fun newPrefs() = WalletPreferences(context, NoopLogger)

    // --- Default network ---

    @Test
    fun `default selected network is MAINNET`() {
        assertEquals(NetworkType.MAINNET, newPrefs().getSelectedNetwork())
    }

    @Test
    fun `setSelectedNetwork to TESTNET persists across instances`() {
        val prefs = newPrefs()
        prefs.setSelectedNetwork(NetworkType.TESTNET)
        assertEquals(NetworkType.TESTNET, newPrefs().getSelectedNetwork())
    }

    @Test
    fun `getSelectedNetwork falls back to MAINNET for unrecognised stored value`() {
        rawPrefs().edit()
            .putString("selected_network", "UNKNOWN_NETWORK")
            .apply()
        assertEquals(NetworkType.MAINNET, newPrefs().getSelectedNetwork())
    }

    // --- Zero-cell rescue rescan flag (knmo: per-launch loop fix) ---

    @Test
    fun `zero-cell rescan flag defaults false, persists, and clears`() {
        val prefs = newPrefs()
        assertFalse(prefs.isZeroCellRescanDone("wallet-1"))
        prefs.setZeroCellRescanDone("wallet-1")
        assertTrue("persists across instances", newPrefs().isZeroCellRescanDone("wallet-1"))
        prefs.clearZeroCellRescanDone("wallet-1")
        assertFalse(newPrefs().isZeroCellRescanDone("wallet-1"))
    }

    @Test
    fun `zero-cell rescan flag is per wallet and per network`() {
        val prefs = newPrefs()
        prefs.setZeroCellRescanDone("wallet-1", NetworkType.MAINNET)
        assertFalse("other wallet unaffected", prefs.isZeroCellRescanDone("wallet-2", NetworkType.MAINNET))
        assertFalse("other network unaffected", prefs.isZeroCellRescanDone("wallet-1", NetworkType.TESTNET))
        assertTrue(prefs.isZeroCellRescanDone("wallet-1", NetworkType.MAINNET))
    }

    // --- Per-network isolation: sync mode ---

    @Test
    fun `sync modes are independent between networks`() {
        val prefs = newPrefs()
        prefs.setSyncMode(SyncMode.RECENT, NetworkType.MAINNET)
        prefs.setSyncMode(SyncMode.FULL_HISTORY, NetworkType.TESTNET)

        assertEquals(SyncMode.RECENT, prefs.getSyncMode(NetworkType.MAINNET))
        assertEquals(SyncMode.FULL_HISTORY, prefs.getSyncMode(NetworkType.TESTNET))
    }

    @Test
    fun `getSyncMode defaults to NEW_WALLET for a network with no stored value`() {
        // Default flipped from RECENT to NEW_WALLET in v1.6.0: a fresh wallet
        // has no past activity to find, so silently scanning the last 30 days
        // (RECENT) was kicking off pointless re-syncs. Callers that need a
        // network-aware first-time default should use getSyncModeOrNull.
        assertEquals(SyncMode.NEW_WALLET, newPrefs().getSyncMode(NetworkType.TESTNET))
    }

    @Test
    fun `getSyncModeOrNull returns null when nothing stored`() {
        assertNull(newPrefs().getSyncModeOrNull(NetworkType.TESTNET))
    }

    @Test
    fun `getSyncModeOrNull returns the explicitly stored value`() {
        val prefs = newPrefs()
        prefs.setSyncMode(SyncMode.FULL_HISTORY, NetworkType.MAINNET)
        assertEquals(SyncMode.FULL_HISTORY, prefs.getSyncModeOrNull(NetworkType.MAINNET))
    }

    // --- Per-network isolation: custom block height ---

    @Test
    fun `custom block heights are independent between networks`() {
        val prefs = newPrefs()
        prefs.setCustomBlockHeight(18_000_000L, NetworkType.MAINNET)
        prefs.setCustomBlockHeight(100_000L, NetworkType.TESTNET)

        assertEquals(18_000_000L, prefs.getCustomBlockHeight(NetworkType.MAINNET))
        assertEquals(100_000L, prefs.getCustomBlockHeight(NetworkType.TESTNET))
    }

    @Test
    fun `getCustomBlockHeight returns null when not set`() {
        assertNull(newPrefs().getCustomBlockHeight(NetworkType.TESTNET))
    }

    @Test
    fun `setCustomBlockHeight with null removes the entry`() {
        val prefs = newPrefs()
        prefs.setCustomBlockHeight(12345L, NetworkType.MAINNET)
        prefs.setCustomBlockHeight(null, NetworkType.MAINNET)
        assertNull(prefs.getCustomBlockHeight(NetworkType.MAINNET))
    }

    // --- Per-network isolation: initial sync ---

    @Test
    fun `completing initial sync on mainnet does not affect testnet`() {
        val prefs = newPrefs()
        prefs.setInitialSyncCompleted(true, NetworkType.MAINNET)

        assertTrue(prefs.hasCompletedInitialSync(NetworkType.MAINNET))
        assertFalse(prefs.hasCompletedInitialSync(NetworkType.TESTNET))
    }

    // --- Migration: pre-testnet upgrade path ---

    @Test
    fun `migration moves old sync_mode to mainnet namespace`() {
        rawPrefs().edit()
            .putString("sync_mode", SyncMode.FULL_HISTORY.name)
            .commit() // no selected_network key → migration hasn't run

        val prefs = newPrefs()

        assertEquals(SyncMode.FULL_HISTORY, prefs.getSyncMode(NetworkType.MAINNET))
        assertFalse("un-namespaced key must be removed", rawPrefs().contains("sync_mode"))
    }

    @Test
    fun `migration moves old custom_block_height to mainnet namespace`() {
        rawPrefs().edit()
            .putLong("custom_block_height", 15_000_000L)
            .commit()

        val prefs = newPrefs()

        assertEquals(15_000_000L, prefs.getCustomBlockHeight(NetworkType.MAINNET))
        assertFalse(rawPrefs().contains("custom_block_height"))
    }

    @Test
    fun `migration moves old initial_sync_completed to mainnet namespace`() {
        rawPrefs().edit()
            .putBoolean("initial_sync_completed", true)
            .commit()

        val prefs = newPrefs()

        assertTrue(prefs.hasCompletedInitialSync(NetworkType.MAINNET))
        assertFalse(rawPrefs().contains("initial_sync_completed"))
    }

    @Test
    fun `migration does not overwrite already-migrated values on second instantiation`() {
        // First run: migrate old sync_mode
        rawPrefs().edit()
            .putString("sync_mode", SyncMode.FULL_HISTORY.name)
            .commit()
        newPrefs() // triggers migration, sets selected_network guard

        // Simulate user changing sync mode after migration
        rawPrefs().edit().putString("mainnet_sync_mode", SyncMode.CUSTOM.name).apply()

        // Second instantiation must not re-migrate (guard key present)
        assertEquals(SyncMode.CUSTOM, newPrefs().getSyncMode(NetworkType.MAINNET))
    }

    @Test
    fun `fresh install with no prior prefs defaults to MAINNET`() {
        // setUp already cleared everything; newPrefs() runs migration on a blank slate
        assertEquals(NetworkType.MAINNET, newPrefs().getSelectedNetwork())
    }

    // --- Network services (#531) ---

    @Test
    fun `price service defaults to enabled`() {
        assertTrue(newPrefs().isPriceServiceEnabled())
    }

    @Test
    fun `update service defaults to enabled`() {
        assertTrue(newPrefs().isUpdateServiceEnabled())
    }

    @Test
    fun `setPriceServiceEnabled round trips across instances`() {
        val prefs = newPrefs()
        prefs.setPriceServiceEnabled(false)
        assertFalse(newPrefs().isPriceServiceEnabled())

        prefs.setPriceServiceEnabled(true)
        assertTrue(newPrefs().isPriceServiceEnabled())
    }

    @Test
    fun `setUpdateServiceEnabled round trips across instances`() {
        val prefs = newPrefs()
        prefs.setUpdateServiceEnabled(false)
        assertFalse(newPrefs().isUpdateServiceEnabled())

        prefs.setUpdateServiceEnabled(true)
        assertTrue(newPrefs().isUpdateServiceEnabled())
    }

    @Test
    fun `priceServiceEnabledFlow reflects the current value and reacts to writes`() {
        val prefs = newPrefs()
        assertTrue(prefs.priceServiceEnabledFlow.value)

        prefs.setPriceServiceEnabled(false)
        assertFalse(prefs.priceServiceEnabledFlow.value)
    }

    // --- Sweep tx-hash/fee marker (#538 review, retry-path follow-up) ---
    // Stored in WalletPreferences itself, not the Room `transactions` cache,
    // because retryBroadcast deletes and re-inserts that row on every retry;
    // a retried sweep must still find its fee afterward. Scoped by
    // walletId+network (#538 review) so it cannot override the classification
    // for a different wallet or network viewing the same hash.

    private val walletA = "wallet-a"
    private val walletB = "wallet-b"

    @Test
    fun `a hash never marked as a sweep has no fee and is not a sweep`() {
        val prefs = newPrefs()
        assertFalse(prefs.isSweepTxHash(walletA, "TESTNET", "0xabc"))
        assertNull(prefs.sweepFeeShannons(walletA, "TESTNET", "0xabc"))
    }

    @Test
    fun `addSweepTxHash records the fee, readable back on a fresh instance`() {
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletA, "TESTNET", "0xsweep1", 1_000L)

        assertTrue(newPrefs().isSweepTxHash(walletA, "TESTNET", "0xsweep1"))
        assertEquals(1_000L, newPrefs().sweepFeeShannons(walletA, "TESTNET", "0xsweep1"))
    }

    @Test
    fun `the sweep fee survives independently of anything Room-side (simulated by never touching it)`() {
        // The whole point of storing the fee here instead of the `transactions`
        // row: retryBroadcast's cacheManager.deleteTransaction(txHash) call
        // cannot reach this preference, so the fee recorded at send time is
        // still there after a simulated "retry" that never touches it again.
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletA, "TESTNET", "0xsweep2", 2_000L)

        // Nothing else in this test writes to or clears sweep prefs, standing
        // in for a Room-row deletion elsewhere: the marker is unaffected.
        assertEquals(2_000L, newPrefs().sweepFeeShannons(walletA, "TESTNET", "0xsweep2"))
    }

    @Test
    fun `multiple sweeps keep independent fees`() {
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletA, "TESTNET", "0xsweepA", 111L)
        prefs.addSweepTxHash(walletA, "TESTNET", "0xsweepB", 222L)

        assertEquals(111L, prefs.sweepFeeShannons(walletA, "TESTNET", "0xsweepA"))
        assertEquals(222L, prefs.sweepFeeShannons(walletA, "TESTNET", "0xsweepB"))
    }

    @Test
    fun `the same hash for a different wallet is not a sweep (unscoped would have leaked)`() {
        // #538 review: an unscoped, hash-only marker would have overridden
        // the classification for ANY wallet viewing this hash. Scoped, wallet
        // B never sees wallet A's sweep.
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletA, "TESTNET", "0xshared", 1_000L)

        assertFalse(prefs.isSweepTxHash(walletB, "TESTNET", "0xshared"))
        assertNull(prefs.sweepFeeShannons(walletB, "TESTNET", "0xshared"))
    }

    @Test
    fun `the same hash and wallet on a different network is not a sweep`() {
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletA, "TESTNET", "0xshared", 1_000L)

        assertFalse(prefs.isSweepTxHash(walletA, "MAINNET", "0xshared"))
        assertNull(prefs.sweepFeeShannons(walletA, "MAINNET", "0xshared"))
    }

    @Test
    fun `clearSweepTxHashes removes only the given wallet's entries`() {
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletA, "TESTNET", "0xsweep1", 111L)
        prefs.addSweepTxHash(walletA, "MAINNET", "0xsweep2", 222L)
        prefs.addSweepTxHash(walletB, "TESTNET", "0xsweep3", 333L)

        prefs.clearSweepTxHashes(walletA)

        assertNull(prefs.sweepFeeShannons(walletA, "TESTNET", "0xsweep1"))
        assertNull(prefs.sweepFeeShannons(walletA, "MAINNET", "0xsweep2"))
        assertEquals(333L, prefs.sweepFeeShannons(walletB, "TESTNET", "0xsweep3"))
    }

    @Test
    fun `clearSweepTxHashes on a wallet with no entries is a harmless no-op`() {
        val prefs = newPrefs()
        prefs.addSweepTxHash(walletB, "TESTNET", "0xsweep3", 333L)

        prefs.clearSweepTxHashes(walletA)

        assertEquals(333L, prefs.sweepFeeShannons(walletB, "TESTNET", "0xsweep3"))
    }
}
