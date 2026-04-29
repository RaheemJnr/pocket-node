package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SyncStrategy { ACTIVE_ONLY, ALL_WALLETS, BALANCED }

/**
 * Manages wallet preferences for persisting user settings like sync mode.
 * All per-network preferences are namespaced by network name to prevent cross-contamination.
 */
@Singleton
class WalletPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // --- sync_progress prefs → Room migration (#105 / #112) ---
    // Self-contained API so SharedPreferences never escapes this class.
    // Remove these three methods once the migration helper is retired.

    /** True once `migrateSyncProgressToRoomIfNeeded` has run successfully. */
    internal fun isSyncProgressMigratedToRoom(): Boolean =
        prefs.getBoolean(KEY_SYNC_PROGRESS_MIGRATED, false)

    /**
     * Read a legacy `${walletId}_${network}_last_synced_block` value.
     * Returns null when the key is absent OR the stored block is <= 0
     * (placeholder values that should not be migrated).
     */
    internal fun getLegacySyncedBlock(walletId: String, network: NetworkType): Long? {
        val key = "${walletId}_${network.name.lowercase()}_last_synced_block"
        if (!prefs.contains(key)) return null
        val block = prefs.getLong(key, 0L)
        return if (block > 0L) block else null
    }

    /**
     * Atomically remove every legacy `*_last_synced_block` key for the supplied
     * wallets/networks AND set the migration guard flag in a single commit.
     * `commit()` (synchronous) so the guard is durable before this method returns —
     * a crash mid-migration leaves the guard unset and the migration retries safely.
     */
    internal fun clearLegacySyncedBlocksAndMarkMigrated(
        walletIds: List<String>,
        networks: List<NetworkType>
    ) {
        val editor = prefs.edit()
        for (walletId in walletIds) {
            for (net in networks) {
                editor.remove("${walletId}_${net.name.lowercase()}_last_synced_block")
            }
        }
        editor.putBoolean(KEY_SYNC_PROGRESS_MIGRATED, true).commit()
    }

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private fun readThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun getThemeMode(): ThemeMode = _themeMode.value

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    init {
        migrateIfNeeded()
    }

    // --- Network selection (global, not namespaced) ---

    fun getSelectedNetwork(): NetworkType {
        val name = prefs.getString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        return try {
            NetworkType.valueOf(name ?: NetworkType.MAINNET.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown network name '$name', defaulting to MAINNET", e)
            NetworkType.MAINNET
        }
    }

    fun setSelectedNetwork(network: NetworkType) {
        // commit() instead of apply() — must flush synchronously before Process.killProcess()
        prefs.edit().putString(KEY_SELECTED_NETWORK, network.name).commit()
    }

    // --- Per-network key helper ---

    private fun networkKey(key: String, network: NetworkType? = null): String {
        val net = network ?: getSelectedNetwork()
        return "${net.name.lowercase()}_$key"
    }

    private fun walletNetworkKey(walletId: String, network: String, key: String): String =
        "${walletId}_${network.lowercase()}_$key"

    // --- Sync mode ---

    fun getSyncMode(network: NetworkType? = null, walletId: String? = null): SyncMode {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_SYNC_MODE)
                  else networkKey(KEY_SYNC_MODE, net)
        val modeName = prefs.getString(key, SyncMode.RECENT.name)
        return try {
            SyncMode.valueOf(modeName ?: SyncMode.RECENT.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown sync mode '$modeName', defaulting to RECENT", e)
            SyncMode.RECENT
        }
    }

    fun setSyncMode(mode: SyncMode, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_SYNC_MODE)
                  else networkKey(KEY_SYNC_MODE, net)
        prefs.edit().putString(key, mode.name).apply()
    }

    // --- Custom block height ---

    fun getCustomBlockHeight(network: NetworkType? = null, walletId: String? = null): Long? {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_CUSTOM_BLOCK_HEIGHT)
                  else networkKey(KEY_CUSTOM_BLOCK_HEIGHT, net)
        val height = prefs.getLong(key, -1L)
        return if (height >= 0) height else null
    }

    fun setCustomBlockHeight(height: Long?, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_CUSTOM_BLOCK_HEIGHT)
                  else networkKey(KEY_CUSTOM_BLOCK_HEIGHT, net)
        if (height != null) {
            prefs.edit().putLong(key, height).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    // --- Initial sync ---

    fun hasCompletedInitialSync(network: NetworkType? = null, walletId: String? = null): Boolean {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_INITIAL_SYNC_COMPLETED)
                  else networkKey(KEY_INITIAL_SYNC_COMPLETED, net)
        return prefs.getBoolean(key, false)
    }

    fun setInitialSyncCompleted(completed: Boolean, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_INITIAL_SYNC_COMPLETED)
                  else networkKey(KEY_INITIAL_SYNC_COMPLETED, net)
        prefs.edit().putBoolean(key, completed).apply()
    }

    // --- Background sync (global, not per-network) ---

    fun isBackgroundSyncEnabled(): Boolean {
        // Default OFF (#116). Previous default was true, but on Android 13+
        // the foreground service requires POST_NOTIFICATIONS to actually run;
        // setting this to true before the user grants notifications produces
        // a misleading "ON" state where the FGS can't post and gets killed
        // silently. Now: explicit opt-in only, gated on permission grant in
        // SettingsScreen.
        return prefs.getBoolean(KEY_BACKGROUND_SYNC, false)
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, enabled).commit()
    }

    // --- Post-deposit "Protect your funds" dialog (per-wallet, #116 follow-up) ---
    //
    // The dialog is intended to fire once when a wallet first receives funds
    // and is not fully secured (no PIN/biometrics OR no recovery-phrase backup).
    // The trigger lives in HomeViewModel and watches `previousBalanceWasZero`,
    // a field that resets to true on every VM init — so on every reopen of
    // a funded-but-unsecured wallet the first balance emission (already > 0
    // from the cached/synced state) re-trips the condition and the dialog
    // returns.
    //
    // Persisting "already shown" per-wallet means each wallet sees the dialog
    // exactly once. If the user secures the wallet later, the regular trigger
    // condition (`!hasPin || !hasBackup`) would be false anyway.

    fun isPostDepositReminderShown(walletId: String): Boolean {
        return prefs.getBoolean(postDepositReminderKey(walletId), false)
    }

    fun setPostDepositReminderShown(walletId: String) {
        prefs.edit().putBoolean(postDepositReminderKey(walletId), true).apply()
    }

    private fun postDepositReminderKey(walletId: String): String =
        "${KEY_POST_DEPOSIT_REMINDER_SHOWN_PREFIX}$walletId"

    // --- Database maintenance ---

    fun getLastVacuumAt(): Long = prefs.getLong(KEY_LAST_VACUUM_AT, 0L)

    fun setLastVacuumAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_VACUUM_AT, timestampMs).apply()
    }

    // --- Active wallet (M3 multi-wallet) ---

    fun getActiveWalletId(): String? = prefs.getString(KEY_ACTIVE_WALLET_ID, null)

    fun setActiveWalletId(walletId: String) {
        prefs.edit().putString(KEY_ACTIVE_WALLET_ID, walletId).apply()
    }

    // --- Sync strategy (M3 multi-wallet) ---

    fun getSyncStrategy(): SyncStrategy {
        val name = prefs.getString(KEY_SYNC_STRATEGY, SyncStrategy.ALL_WALLETS.name)
        return try {
            SyncStrategy.valueOf(name ?: SyncStrategy.ALL_WALLETS.name)
        } catch (_: Exception) {
            SyncStrategy.ALL_WALLETS
        }
    }

    fun setSyncStrategy(strategy: SyncStrategy) {
        prefs.edit().putString(KEY_SYNC_STRATEGY, strategy.name).apply()
    }

    // --- Sync coachmark (first-run education, global) ---

    private val _hasSeenSyncCoachmark =
        MutableStateFlow(prefs.getBoolean(KEY_SYNC_COACHMARK_SEEN, false))
    val hasSeenSyncCoachmarkFlow: StateFlow<Boolean> = _hasSeenSyncCoachmark.asStateFlow()

    fun markSyncCoachmarkSeen() {
        prefs.edit().putBoolean(KEY_SYNC_COACHMARK_SEEN, true).apply()
        _hasSeenSyncCoachmark.value = true
    }

    // --- Utilities ---

    // Clearing prefs removes KEY_SELECTED_NETWORK, so migrateIfNeeded() re-runs on next startup.
    // That's benign: old un-namespaced keys are already gone, it just re-sets default to MAINNET.
    fun clear() {
        prefs.edit().clear().apply()
        // Re-synchronize StateFlows seeded from prefs at construction so observers
        // don't read stale state until process restart.
        _hasSeenSyncCoachmark.value = false
        _themeMode.value = readThemeMode()
    }

    /**
     * One-time migration: moves old un-namespaced keys to mainnet-namespaced keys.
     * Existing users upgrading from pre-testnet versions have un-namespaced sync prefs
     * that belong to mainnet. This copies them to "mainnet_" prefixed keys.
     */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_SELECTED_NETWORK)) return // already migrated

        val editor = prefs.edit()
        val mainnetPrefix = "${NetworkType.MAINNET.name.lowercase()}_"

        // Migrate sync_mode
        prefs.getString(KEY_SYNC_MODE, null)?.let { oldValue ->
            editor.putString("${mainnetPrefix}$KEY_SYNC_MODE", oldValue)
            editor.remove(KEY_SYNC_MODE)
        }

        // Migrate custom_block_height
        if (prefs.contains(KEY_CUSTOM_BLOCK_HEIGHT)) {
            val oldValue = prefs.getLong(KEY_CUSTOM_BLOCK_HEIGHT, -1L)
            if (oldValue >= 0) {
                editor.putLong("${mainnetPrefix}$KEY_CUSTOM_BLOCK_HEIGHT", oldValue)
            }
            editor.remove(KEY_CUSTOM_BLOCK_HEIGHT)
        }

        // Migrate initial_sync_completed
        if (prefs.contains(KEY_INITIAL_SYNC_COMPLETED)) {
            val oldValue = prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
            editor.putBoolean("${mainnetPrefix}$KEY_INITIAL_SYNC_COMPLETED", oldValue)
            editor.remove(KEY_INITIAL_SYNC_COMPLETED)
        }

        // Set default network (always, even if no old keys existed)
        editor.putString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        editor.commit() // Synchronous to ensure migration guard persists before process death
    }

    companion object {
        private const val TAG = "WalletPreferences"
        private const val PREFS_NAME = "ckb_wallet_prefs"
        private const val KEY_SELECTED_NETWORK = "selected_network"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_CUSTOM_BLOCK_HEIGHT = "custom_block_height"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
        private const val KEY_ACTIVE_WALLET_ID = "active_wallet_id"
        private const val KEY_SYNC_STRATEGY = "sync_strategy"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BACKGROUND_SYNC = "background_sync_enabled"
        private const val KEY_POST_DEPOSIT_REMINDER_SHOWN_PREFIX = "post_deposit_reminder_shown_"
        private const val KEY_LAST_VACUUM_AT = "last_vacuum_at"
        private const val KEY_SYNC_PROGRESS_MIGRATED = "sync_progress_migrated_to_room_v7"
        private const val KEY_SYNC_COACHMARK_SEEN = "sync_coachmark_seen"
    }
}
