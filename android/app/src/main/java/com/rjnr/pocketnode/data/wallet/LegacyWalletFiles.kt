package com.rjnr.pocketnode.data.wallet

import android.content.Context
import java.io.File

/** The only resolver for historical wallet preference namespaces. */
internal object LegacyWalletNamespace {
    const val DEFAULT = "ckb_wallet_keys"
    fun preferences(walletId: String): String {
        require(walletId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return if (walletId == "default") DEFAULT else "${DEFAULT}_$walletId"
    }
    fun owns(name: String) = name == DEFAULT || name.startsWith("${DEFAULT}_")
}

/** Destructive deletion only: never constructs ESP, decrypts a keyset, or generates a master key. */
internal class LegacyWalletFiles(private val context: Context) {
    private val directory get() = File(context.applicationInfo.dataDir, "shared_prefs")
    private fun entries(): List<File> = if (!directory.exists()) emptyList() else
        directory.listFiles()?.toList() ?: error("Wallet preference directory could not be read.")
    private fun name(file: File): String? {
        val name = when {
            file.name.endsWith(".xml.bak") -> file.name.removeSuffix(".xml.bak")
            file.name.endsWith(".xml") -> file.name.removeSuffix(".xml")
            else -> return null
        }
        return name.takeIf(LegacyWalletNamespace::owns)
    }
    fun exists(walletId: String): Boolean {
        val name = LegacyWalletNamespace.preferences(walletId)
        return File(directory, "$name.xml").exists() || File(directory, "$name.xml.bak").exists()
    }
    fun deleteWallet(walletId: String) = deletePreference(LegacyWalletNamespace.preferences(walletId))
    fun deleteAll() {
        // Includes the accidentally named ckb_wallet_keys_default bucket and orphaned scopes.
        (entries().mapNotNull(::name) + LegacyWalletNamespace.DEFAULT).distinct().forEach(::deletePreference)
        verifyEmpty()
    }
    fun verifyEmpty() { check(entries().none { name(it) != null }) { "Wallet preferences could not be removed." } }
    private fun deletePreference(name: String) {
        val xml = File(directory, "$name.xml")
        val bak = File(directory, "$name.xml.bak")
        check(!xml.isDirectory && !bak.isDirectory) { "Wallet preference path could not be removed." }
        // Android caches raw SharedPreferencesImpl across ESP wrappers. Clear that same cached
        // map synchronously before unlinking; deleting only the XML leaves live secrets in it.
        check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Wallet preference cache could not be cleared."
        }
        for (copy in listOf(xml, bak)) {
            check(!copy.exists() || copy.delete()) { "Wallet preferences could not be removed." }
        }
        check(!xml.exists() && !bak.exists()) { "Wallet preference copies remain." }
    }
}
