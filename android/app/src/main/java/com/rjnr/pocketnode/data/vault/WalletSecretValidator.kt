package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletDerivation

/** Pure derivation, deliberately independent of the authenticated key reader. */
class WalletSecretValidator(private val mnemonicManager: MnemonicManager) {
    fun validate(material: KeyMaterial, wallet: WalletEntity?): Boolean {
        var key: ByteArray? = null
        try {
            val hex = material.privateKey.removePrefix("0x")
            if (!hex.matches(Regex("[0-9a-fA-F]{64}"))) return false
            key = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val info = WalletDerivation.walletInfo(key)
            if (wallet != null) {
                val compatibleType = wallet.type == material.walletType ||
                    wallet.type == "mnemonic" && material.walletType == "raw_key"
                if (!compatibleType || wallet.mainnetAddress != info.mainnetAddress ||
                    wallet.testnetAddress != info.testnetAddress) return false
            }
            val path = wallet?.derivationPath
            val indices = if (path == null) listOf(wallet?.accountIndex ?: 0, 0, 0) else {
                val match = Regex("m/44'/309'/([0-9]+)'/([01])/([0-9]+)").matchEntire(path) ?: return false
                match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            }
            if (indices.any { it < 0 }) return false
            when (material.walletType) {
                // A recovered private key restores this wallet's spending, never its seed/HD capabilities.
                "raw_key" -> if (material.mnemonic != null || path != null && wallet.type != "mnemonic") return false
                "mnemonic" -> {
                    val words = material.mnemonic?.split(" ") ?: return false
                    val seed = mnemonicManager.mnemonicToSeed(words)
                    try {
                        val derived = mnemonicManager.derivePrivateKey(seed, indices[0], indices[1], indices[2])
                        try { if (!key.contentEquals(derived)) return false }
                        finally { derived.fill(0) }
                    } finally { seed.fill(0) }
                }
                else -> return false
            }
            return true
        } catch (_: Exception) { return false }
        finally { key?.fill(0) }
    }
}
