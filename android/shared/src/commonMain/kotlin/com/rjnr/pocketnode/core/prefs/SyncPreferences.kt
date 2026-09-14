package com.rjnr.pocketnode.core.prefs

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode

/** How many wallets the light client keeps registered at once (M3 multi-wallet). */
enum class SyncStrategy { ACTIVE_ONLY, ALL_WALLETS, BALANCED }

/**
 * Everything the chain-sync path persists: which mode/height a wallet syncs
 * from, whether its first sync finished, the background-sync switch and the
 * bookkeeping the sync loop writes back.
 *
 * Android implements this on `SharedPreferences`
 * (`com.rjnr.pocketnode.data.wallet.WalletPreferences`); iOS implements it on
 * `UserDefaults` (D0/D1 in docs/IOS_M1_DESIGN.md). Keys, defaults and the
 * per-network namespacing are the implementation's business: a null `network`
 * means "the currently selected one", a null `walletId` means "the
 * network-wide value rather than a per-wallet one".
 */
interface SyncPreferences {

    // --- Sync mode ---

    /**
     * The explicitly-stored sync mode, or null when nothing has ever been
     * written for this wallet/network. Lets callers tell "user picked RECENT"
     * apart from "nothing written yet" without re-reading raw prefs.
     */
    fun getSyncModeOrNull(network: NetworkType? = null, walletId: String? = null): SyncMode?

    /** [getSyncModeOrNull] with the implementation's default applied. */
    fun getSyncMode(network: NetworkType? = null, walletId: String? = null): SyncMode

    fun setSyncMode(mode: SyncMode, network: NetworkType? = null, walletId: String? = null)

    // --- Custom block height ---

    fun getCustomBlockHeight(network: NetworkType? = null, walletId: String? = null): Long?

    fun setCustomBlockHeight(height: Long?, network: NetworkType? = null, walletId: String? = null)

    // --- Initial sync ---

    fun hasCompletedInitialSync(network: NetworkType? = null, walletId: String? = null): Boolean

    fun setInitialSyncCompleted(
        completed: Boolean,
        network: NetworkType? = null,
        walletId: String? = null
    )

    // --- Zero-live-cell rescue rescan (#332) ---

    fun isZeroCellRescanDone(walletId: String, network: NetworkType? = null): Boolean

    fun setZeroCellRescanDone(walletId: String, network: NetworkType? = null)

    fun clearZeroCellRescanDone(walletId: String, network: NetworkType? = null)

    // --- Background sync (global, not per-network) ---

    fun isBackgroundSyncEnabled(): Boolean

    fun setBackgroundSyncEnabled(enabled: Boolean)

    /** Wall-clock of the last observed sync progress (#286). 0 = never synced. */
    fun getLastSyncedAt(): Long

    fun setLastSyncedAt(timestampMs: Long)

    // --- Sync strategy (M3 multi-wallet) ---

    fun getSyncStrategy(): SyncStrategy

    fun setSyncStrategy(strategy: SyncStrategy)

    // --- Gap-limit signature detection (#382) ---

    fun isGapLimitSignalDetected(network: NetworkType? = null, walletId: String? = null): Boolean

    fun setGapLimitSignalDetected(
        detected: Boolean,
        network: NetworkType? = null,
        walletId: String? = null
    )
}
