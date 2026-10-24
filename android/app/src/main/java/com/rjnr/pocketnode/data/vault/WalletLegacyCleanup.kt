package com.rjnr.pocketnode.data.vault

import android.content.Context
import com.rjnr.pocketnode.data.crypto.KeyBackupManager

/** Wallet-specific retirement only; shared Android key aliases are never deleted. */
class WalletLegacyCleanup(private val context: Context, private val backup: KeyBackupManager) : VaultLegacyCleanup {
    override suspend fun clean(walletId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!walletId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return@withContext false
        if (!backup.deleteBackupChecked(walletId)) return@withContext false
        val name = if (walletId == "default") "ckb_wallet_keys" else "ckb_wallet_keys_$walletId"
        val file = java.io.File(context.filesDir.parentFile, "shared_prefs/$name.xml")
        val pending = java.io.File(file.path + ".bak")
        if (file.exists() || pending.exists()) context.deleteSharedPreferences(name)
        !file.exists() && !pending.exists()
    }
}
