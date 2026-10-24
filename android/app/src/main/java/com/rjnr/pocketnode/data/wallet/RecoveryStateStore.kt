package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent state of a Recovery screen restore that has not finished.
 *
 * Wallet ids remain unverified after missing keys, failed authentication or pending
 * legacy cleanup. Only an authenticated recovery success removes an id; merely
 * entering the dashboard in read-only mode does not clear this state.
 *
 * Plain (not encrypted) app prefs: it holds only wallet ids and a flag.
 */
@Singleton
class RecoveryStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isIncomplete(): Boolean = prefs.getBoolean(KEY_INCOMPLETE, false)

    fun markIncomplete() {
        prefs.edit().putBoolean(KEY_INCOMPLETE, true).commit()
    }

    /** Wallets whose key rows were written by the restore but not verified yet. */
    fun unverifiedWalletIds(): Set<String> =
        prefs.getStringSet(KEY_UNVERIFIED, emptySet())?.toSet() ?: emptySet()

    fun addUnverified(walletId: String) {
        prefs.edit().putStringSet(KEY_UNVERIFIED, unverifiedWalletIds() + walletId).commit()
    }

    fun removeUnverified(walletId: String) {
        if (walletId !in unverifiedWalletIds()) return
        prefs.edit().putStringSet(KEY_UNVERIFIED, unverifiedWalletIds() - walletId).commit()
    }

    /** Recovery is complete: drop the marker and the unverified set. */
    fun clear() {
        prefs.edit().remove(KEY_INCOMPLETE).remove(KEY_UNVERIFIED).commit()
    }

    private companion object {
        const val PREFS_NAME = "recovery_state"
        const val KEY_INCOMPLETE = "recovery_incomplete"
        const val KEY_UNVERIFIED = "unverified_wallet_ids"
    }
}
