package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Immutable encrypted source, retained independently of weaker-backup cleanup. */
@Entity(tableName = "wallet_vault_legacy")
data class WalletVaultLegacyEntity(
    @PrimaryKey val walletId: String,
    val encryptedPrivateKey: ByteArray,
    val encryptedMnemonic: ByteArray?,
    val iv: ByteArray,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val updatedAt: Long,
    val kdfVersion: Int,
) {
    fun source() = KeyMaterialEntity(walletId, encryptedPrivateKey, encryptedMnemonic, iv,
        walletType, mnemonicBackedUp, updatedAt, kdfVersion)
    override fun toString() = "WalletVaultLegacyEntity([redacted])"
}
