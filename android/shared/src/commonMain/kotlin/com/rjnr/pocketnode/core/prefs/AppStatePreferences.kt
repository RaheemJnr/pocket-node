package com.rjnr.pocketnode.core.prefs

/**
 * Process-level bookkeeping that is neither a sync setting nor a UI
 * preference: which wallet is active, which app version last ran, and when
 * the database was last compacted.
 *
 * These are the values read once at cold start (or when the active wallet
 * changes) rather than per sync poll or per screen.
 */
interface AppStatePreferences {

    // --- Active wallet (M3 multi-wallet) ---

    fun getActiveWalletId(): String?

    fun setActiveWalletId(walletId: String)

    /**
     * Drop the persisted active-wallet pointer. Used by the Forgot-PIN
     * factory-reset path so the active-wallet guard in `deleteWallet` does
     * not block bulk deletion.
     */
    fun clearActiveWalletId()

    // --- App version (#370) ---

    /**
     * Last app versionCode seen at cold start. 0 = never recorded (fresh
     * install). Used to detect an overwrite install / upgrade and reset the
     * PIN failed-attempt counter once.
     */
    fun getLastSeenVersionCode(): Int

    fun setLastSeenVersionCode(code: Int)

    // --- Database maintenance ---

    fun getLastVacuumAt(): Long

    fun setLastVacuumAt(timestampMs: Long)
}
