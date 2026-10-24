package com.rjnr.pocketnode.data.wallet

import android.content.Context
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A deliberate user choice, accessible before any wallet secret is unlocked. */
@Singleton
class WalletUnlockPreference @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("wallet_unlock_method", Context.MODE_PRIVATE)
    fun get(): VaultReadPreference = runCatching {
        VaultReadPreference.valueOf(prefs.getString("method", null) ?: "DEFAULT")
    }.getOrDefault(VaultReadPreference.DEFAULT)
    fun set(value: VaultReadPreference) { prefs.edit().putString("method", value.name).apply() }
}
