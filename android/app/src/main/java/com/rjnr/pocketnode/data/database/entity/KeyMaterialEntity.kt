package com.rjnr.pocketnode.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "key_material")
data class KeyMaterialEntity(
    @PrimaryKey val walletId: String,
    val encryptedPrivateKey: ByteArray,
    val encryptedMnemonic: ByteArray?,
    val iv: ByteArray,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val updatedAt: Long,
    /**
     * KDF version for the key material encryption.
     *
     * - **1** (legacy, v1.6.x and earlier): encrypted under the unrestricted V1
     *   Keystore key (`pocket_node_key_material`). `encryptedPrivateKey` holds
     *   just the private key ciphertext; `encryptedMnemonic` (if non-null) is
     *   `mnemonicIv + mnemonicCiphertext`. `iv` is the private key IV.
     * - **2** (v1.7.0+): encrypted under the auth-bound V2 Keystore key
     *   (`pocket_node_key_material_v2`). `encryptedPrivateKey` holds a single
     *   bundle ciphertext (JSON of `WalletKeyBundle` with both private key
     *   and mnemonic); `encryptedMnemonic` is always null; `iv` is the bundle
     *   IV. Decrypting V2 requires a fresh BiometricPrompt-bound CryptoObject.
     *
     * Rows added on a fresh v1.7.0 install start at version 2. Existing v1.6.x
     * rows enter v1.7.0 at version 1 and are migrated to version 2 on first
     * launch (one BiometricPrompt per wallet).
     */
    @ColumnInfo(defaultValue = "1")
    val kdfVersion: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyMaterialEntity) return false
        val mnemonicEqual = when {
            encryptedMnemonic == null && other.encryptedMnemonic == null -> true
            encryptedMnemonic == null || other.encryptedMnemonic == null -> false
            else -> encryptedMnemonic.contentEquals(other.encryptedMnemonic)
        }
        return walletId == other.walletId &&
            encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
            mnemonicEqual &&
            iv.contentEquals(other.iv) &&
            walletType == other.walletType &&
            mnemonicBackedUp == other.mnemonicBackedUp &&
            updatedAt == other.updatedAt &&
            kdfVersion == other.kdfVersion
    }

    override fun hashCode(): Int {
        var result = walletId.hashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + (encryptedMnemonic?.contentHashCode() ?: 0)
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + walletType.hashCode()
        result = 31 * result + mnemonicBackedUp.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + kdfVersion.hashCode()
        return result
    }
}
