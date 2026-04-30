package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    private fun newPrefs() = WalletPreferences(context)

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
}
