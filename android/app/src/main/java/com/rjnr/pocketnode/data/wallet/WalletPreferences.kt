package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.AppStatePreferences
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.core.prefs.ThemeMode
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single SharedPreferences-backed implementation of every preference
 * domain (#461). The domains themselves are declared as small interfaces in
 * `com.rjnr.pocketnode.core.prefs` so iOS can implement them on UserDefaults;
 * keys, defaults, the per-network namespacing and the one-time key migration
 * stay here, in one file, so there is exactly one place where a preference
 * key is spelled.
 *
 * Consumers should inject the narrowest interface they use
 * ([SyncPreferences], [UiPreferences], [NetworkPreferences],
 * [AppStatePreferences]); Hilt binds all four to this singleton in
 * `di/SharedModule.kt`. Injecting the concrete class is reserved for the few
 * callers that span three domains or need the Android-only reactive
 * properties / legacy migration API below.
 *
 * All per-network preferences are namespaced by network name to prevent
 * cross-contamination.
 */
@Singleton
class WalletPreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val logger: Logger,
) : SyncPreferences, UiPreferences, NetworkPreferences, AppStatePreferences {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // --- sync_progress prefs → Room migration (#105 / #112) ---
    // Self-contained API so SharedPreferences never escapes this class.
    // Deliberately not on any of the shared interfaces: it is a one-shot
    // Android upgrade path, not a preference domain iOS will ever have.
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

    /** Android-only reactive view of [getThemeMode]; not on [UiPreferences]. */
    val themeModeFlow: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private fun readThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    override fun getThemeMode(): ThemeMode = _themeMode.value

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    /**
     * Hidden "bulk airdrop" send mode. Off for everyone by default; unlocked
     * per-device by a secret tap gesture on the Send screen (founder-only
     * easter egg). Persisted so it stays unlocked once activated.
     */
    override fun isBulkSendUnlocked(): Boolean = prefs.getBoolean(KEY_BULK_SEND_UNLOCKED, false)

    override fun setBulkSendUnlocked(unlocked: Boolean) {
        prefs.edit().putBoolean(KEY_BULK_SEND_UNLOCKED, unlocked).apply()
    }

    /**
     * Tx hashes broadcast as batches of a bulk airdrop. Used to badge those rows
     * as "Bulk" in the activity list. Tx hashes are globally unique, so a single
     * set across wallets/networks is fine; the set only grows on the rare bulk
     * send (founder easter egg).
     */
    override fun isBulkTxHash(hash: String): Boolean =
        prefs.getStringSet(KEY_BULK_TX_HASHES, emptySet())?.contains(hash) == true

    override fun addBulkTxHash(hash: String) {
        // Copy the returned set before mutating — SharedPreferences hands back a
        // shared instance that must not be modified in place.
        val current = prefs.getStringSet(KEY_BULK_TX_HASHES, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_BULK_TX_HASHES, current + hash).apply()
    }

    init {
        migrateIfNeeded()
    }

    // --- Network selection (global, not namespaced) ---

    override fun getSelectedNetwork(): NetworkType {
        val name = prefs.getString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        return try {
            NetworkType.valueOf(name ?: NetworkType.MAINNET.name)
        } catch (e: IllegalArgumentException) {
            logger.w(TAG, "Unknown network name '$name', defaulting to MAINNET", e)
            NetworkType.MAINNET
        }
    }

    override fun setSelectedNetwork(network: NetworkType) {
        // commit() instead of apply() — must flush synchronously before Process.killProcess()
        prefs.edit().putString(KEY_SELECTED_NETWORK, network.name).commit()
    }

    /**
     * Last app versionCode seen at cold start (#370). Default 0 = never
     * recorded (fresh install). Used to detect an overwrite install / upgrade
     * and reset the PIN failed-attempt counter once.
     */
    override fun getLastSeenVersionCode(): Int = prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)

    override fun setLastSeenVersionCode(code: Int) {
        // commit(): the upgrade check runs during cold start before the PIN
        // gate; the write must land before a possible Process.killProcess().
        prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, code).commit()
    }

    // --- Per-network key helper ---

    private fun networkKey(key: String, network: NetworkType? = null): String {
        val net = network ?: getSelectedNetwork()
        return "${net.name.lowercase()}_$key"
    }

    private fun walletNetworkKey(walletId: String, network: String, key: String): String =
        "${walletId}_${network.lowercase()}_$key"

    // --- Sync mode ---

    /**
     * Returns the explicitly-stored sync mode, or null if no value has ever
     * been written for this wallet/network. Lets callers distinguish
     * "user picked RECENT" from "nothing written yet" without re-reading
     * raw prefs.
     *
     * Most call sites should prefer this over [getSyncMode] when handling
     * the first-registration path, so a freshly-created wallet whose
     * per-wallet key was set by `markFreshWalletSyncMode` is not silently
     * overwritten by a network-default heuristic.
     */
    override fun getSyncModeOrNull(network: NetworkType?, walletId: String?): SyncMode? {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_SYNC_MODE)
                  else networkKey(KEY_SYNC_MODE, net)
        val modeName = prefs.getString(key, null) ?: return null
        return runCatching { SyncMode.valueOf(modeName) }
            .onFailure { logger.w(TAG, "Unknown sync mode '$modeName' in prefs", it) }
            .getOrNull()
    }

    override fun getSyncMode(network: NetworkType?, walletId: String?): SyncMode {
        // Default to NEW_WALLET when nothing is explicitly stored. For a fresh
        // wallet there is no past activity to find, and choosing RECENT here
        // would silently kick off a 30-day re-scan that the user didn't ask for.
        // Callers that need a network-aware first-time default should call
        // [getSyncModeOrNull] and apply their own fallback.
        return getSyncModeOrNull(network, walletId) ?: SyncMode.NEW_WALLET
    }

    override fun setSyncMode(mode: SyncMode, network: NetworkType?, walletId: String?) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_SYNC_MODE)
                  else networkKey(KEY_SYNC_MODE, net)
        prefs.edit().putString(key, mode.name).apply()
    }

    // --- Custom block height ---

    override fun getCustomBlockHeight(network: NetworkType?, walletId: String?): Long? {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_CUSTOM_BLOCK_HEIGHT)
                  else networkKey(KEY_CUSTOM_BLOCK_HEIGHT, net)
        val height = prefs.getLong(key, -1L)
        return if (height >= 0) height else null
    }

    override fun setCustomBlockHeight(height: Long?, network: NetworkType?, walletId: String?) {
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

    override fun hasCompletedInitialSync(network: NetworkType?, walletId: String?): Boolean {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_INITIAL_SYNC_COMPLETED)
                  else networkKey(KEY_INITIAL_SYNC_COMPLETED, net)
        return prefs.getBoolean(key, false)
    }

    override fun setInitialSyncCompleted(completed: Boolean, network: NetworkType?, walletId: String?) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_INITIAL_SYNC_COMPLETED)
                  else networkKey(KEY_INITIAL_SYNC_COMPLETED, net)
        prefs.edit().putBoolean(key, completed).apply()
    }

    // --- Zero-live-cell rescue rescan (#332 / knmo) ---
    // The rescue rescan rewinds the script to find cells the light client may
    // have missed. For a wallet whose primary address legitimately has no live
    // cells (e.g. funds on HD-derived addresses created by another wallet) the
    // rescan finds nothing. The in-memory once-per-process latch stopped the
    // within-session loop, but every cold start re-armed and re-rewound, so the
    // user saw "Catching up from..." on every launch. This persists the attempt
    // per wallet+network so it does not repeat each launch; cleared only by an
    // explicit resync.

    override fun isZeroCellRescanDone(walletId: String, network: NetworkType?): Boolean {
        val net = network ?: getSelectedNetwork()
        return prefs.getBoolean(walletNetworkKey(walletId, net.name, KEY_ZERO_CELL_RESCAN_DONE), false)
    }

    override fun setZeroCellRescanDone(walletId: String, network: NetworkType?) {
        val net = network ?: getSelectedNetwork()
        prefs.edit().putBoolean(walletNetworkKey(walletId, net.name, KEY_ZERO_CELL_RESCAN_DONE), true).apply()
    }

    override fun clearZeroCellRescanDone(walletId: String, network: NetworkType?) {
        val net = network ?: getSelectedNetwork()
        prefs.edit().remove(walletNetworkKey(walletId, net.name, KEY_ZERO_CELL_RESCAN_DONE)).apply()
    }

    // --- Background sync (global, not per-network) ---

    override fun isBackgroundSyncEnabled(): Boolean {
        // Default OFF (#116). Previous default was true, but on Android 13+
        // the foreground service requires POST_NOTIFICATIONS to actually run;
        // setting this to true before the user grants notifications produces
        // a misleading "ON" state where the FGS can't post and gets killed
        // silently. Now: explicit opt-in only, gated on permission grant in
        // SettingsScreen.
        return prefs.getBoolean(KEY_BACKGROUND_SYNC, false)
    }

    override fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, enabled).commit()
    }

    /**
     * Wall-clock of the last observed sync progress (#286). Written by the
     * sync poll (throttled there to ~1/min); read by the Home staleness
     * pill. 0 = never synced.
     */
    override fun getLastSyncedAt(): Long = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)

    override fun setLastSyncedAt(timestampMs: Long) {
        // apply(), not commit(): hot path (sync poll), durability loss of one
        // sample is harmless.
        prefs.edit().putLong(KEY_LAST_SYNCED_AT, timestampMs).apply()
    }

    /** Home staleness pill dismissal (#286) — dismiss is permanent, not a nag. */
    override fun isBgSyncPillDismissed(): Boolean = prefs.getBoolean(KEY_BG_SYNC_PILL_DISMISSED, false)

    override fun setBgSyncPillDismissed() {
        prefs.edit().putBoolean(KEY_BG_SYNC_PILL_DISMISSED, true).apply()
    }

    // --- #382 gap-limit signature banner ---
    // Set when transaction history shows an outgoing tx whose change went to
    // no script we track (seed also used in Neuron/standard BIP44 wallets).
    // Sticky per wallet+network; the Tier 2 deep scan clears it.

    override fun isGapLimitSignalDetected(network: NetworkType?, walletId: String?): Boolean {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_GAP_LIMIT_SIGNAL)
                  else networkKey(KEY_GAP_LIMIT_SIGNAL, net)
        return prefs.getBoolean(key, false)
    }

    override fun setGapLimitSignalDetected(detected: Boolean, network: NetworkType?, walletId: String?) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_GAP_LIMIT_SIGNAL)
                  else networkKey(KEY_GAP_LIMIT_SIGNAL, net)
        prefs.edit().putBoolean(key, detected).apply()
    }

    override fun isGapLimitBannerDismissed(network: NetworkType?, walletId: String?): Boolean {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_GAP_LIMIT_BANNER_DISMISSED)
                  else networkKey(KEY_GAP_LIMIT_BANNER_DISMISSED, net)
        return prefs.getBoolean(key, false)
    }

    override fun setGapLimitBannerDismissed(network: NetworkType?, walletId: String?) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_GAP_LIMIT_BANNER_DISMISSED)
                  else networkKey(KEY_GAP_LIMIT_BANNER_DISMISSED, net)
        prefs.edit().putBoolean(key, true).apply()
    }

    // --- Database maintenance ---

    override fun getLastVacuumAt(): Long = prefs.getLong(KEY_LAST_VACUUM_AT, 0L)

    override fun setLastVacuumAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_VACUUM_AT, timestampMs).apply()
    }

    // --- Active wallet (M3 multi-wallet) ---

    override fun getActiveWalletId(): String? = prefs.getString(KEY_ACTIVE_WALLET_ID, null)

    override fun setActiveWalletId(walletId: String) {
        prefs.edit().putString(KEY_ACTIVE_WALLET_ID, walletId).apply()
    }

    /**
     * Drop the persisted active-wallet pointer. Used by the Forgot-PIN
     * factory-reset path so that the active-wallet guard in
     * [WalletRepository.deleteWallet] does not block bulk deletion.
     */
    override fun clearActiveWalletId() {
        prefs.edit().remove(KEY_ACTIVE_WALLET_ID).apply()
    }

    // --- Sync strategy (M3 multi-wallet) ---

    override fun getSyncStrategy(): SyncStrategy {
        val name = prefs.getString(KEY_SYNC_STRATEGY, SyncStrategy.ALL_WALLETS.name)
        return try {
            SyncStrategy.valueOf(name ?: SyncStrategy.ALL_WALLETS.name)
        } catch (_: Exception) {
            SyncStrategy.ALL_WALLETS
        }
    }

    override fun setSyncStrategy(strategy: SyncStrategy) {
        prefs.edit().putString(KEY_SYNC_STRATEGY, strategy.name).apply()
    }

    // --- Sync coachmark (first-run education, global) ---

    private val _hasSeenSyncCoachmark =
        MutableStateFlow(prefs.getBoolean(KEY_SYNC_COACHMARK_SEEN, false))

    /** Android-only reactive view of [hasSeenSyncCoachmark]; not on [UiPreferences]. */
    val hasSeenSyncCoachmarkFlow: StateFlow<Boolean> = _hasSeenSyncCoachmark.asStateFlow()

    override fun hasSeenSyncCoachmark(): Boolean = _hasSeenSyncCoachmark.value

    override fun markSyncCoachmarkSeen() {
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
        private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_CUSTOM_BLOCK_HEIGHT = "custom_block_height"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
        private const val KEY_ZERO_CELL_RESCAN_DONE = "zero_cell_rescan_done"
        private const val KEY_ACTIVE_WALLET_ID = "active_wallet_id"
        private const val KEY_SYNC_STRATEGY = "sync_strategy"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BULK_SEND_UNLOCKED = "bulk_send_unlocked"
        private const val KEY_BULK_TX_HASHES = "bulk_tx_hashes"
        private const val KEY_BACKGROUND_SYNC = "background_sync_enabled"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at_ms"
        private const val KEY_BG_SYNC_PILL_DISMISSED = "bg_sync_pill_dismissed"
        private const val KEY_GAP_LIMIT_SIGNAL = "gap_limit_signal"
        private const val KEY_GAP_LIMIT_BANNER_DISMISSED = "gap_limit_banner_dismissed"
        private const val KEY_LAST_VACUUM_AT = "last_vacuum_at"
        private const val KEY_SYNC_PROGRESS_MIGRATED = "sync_progress_migrated_to_room_v7"
        private const val KEY_SYNC_COACHMARK_SEEN = "sync_coachmark_seen"
    }
}
