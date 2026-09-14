package com.rjnr.pocketnode.core.prefs

import com.rjnr.pocketnode.data.gateway.models.NetworkType

/** App theme choice; SYSTEM follows the platform setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Presentation-only state: what the user picked to look at (theme), what
 * they have already been taught (coachmark) or dismissed (staleness pill,
 * gap-limit banner), and the hidden bulk-send surface.
 *
 * Nothing here affects the chain, a balance or a transaction — losing any of
 * it would only re-show a hint. Android exposes the reactive variants
 * (`themeModeFlow`, `hasSeenSyncCoachmarkFlow`) on the concrete
 * `WalletPreferences`; those stay off this interface so the shared module
 * keeps its zero-dependency core (D1).
 */
interface UiPreferences {

    // --- Theme ---

    fun getThemeMode(): ThemeMode

    fun setThemeMode(mode: ThemeMode)

    // --- Sync coachmark (first-run education, global) ---

    fun hasSeenSyncCoachmark(): Boolean

    fun markSyncCoachmarkSeen()

    // --- Home staleness pill (#286) — dismissal is permanent, not a nag ---

    fun isBgSyncPillDismissed(): Boolean

    fun setBgSyncPillDismissed()

    // --- Gap-limit banner (#382), sticky per wallet+network ---

    fun isGapLimitBannerDismissed(network: NetworkType? = null, walletId: String? = null): Boolean

    fun setGapLimitBannerDismissed(network: NetworkType? = null, walletId: String? = null)

    // --- Bulk send (founder easter egg) ---

    /**
     * Hidden "bulk airdrop" send mode. Off for everyone by default; unlocked
     * per-device by a secret tap gesture on the Send screen. Persisted so it
     * stays unlocked once activated.
     */
    fun isBulkSendUnlocked(): Boolean

    fun setBulkSendUnlocked(unlocked: Boolean)

    /**
     * Tx hashes broadcast as batches of a bulk airdrop, used to badge those
     * rows as "Bulk" in the activity list.
     */
    fun isBulkTxHash(hash: String): Boolean

    fun addBulkTxHash(hash: String)
}
