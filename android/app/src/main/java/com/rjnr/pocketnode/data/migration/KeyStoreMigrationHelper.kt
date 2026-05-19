package com.rjnr.pocketnode.data.migration

import android.content.SharedPreferences
import android.util.Log
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity

data class DecryptedKeyData(
    val privateKeyHex: String,
    val mnemonic: String?,
    val walletType: String,
    val mnemonicBackedUp: Boolean
)

class KeyStoreMigrationHelper(
    private val keyMaterialDao: KeyMaterialDao,
    private val encryptionManager: KeystoreEncryptionManager,
    private val migrationPrefs: SharedPreferences
) {

    suspend fun migrateWallet(
        walletId: String,
        privateKeyHex: String,
        mnemonic: String?,
        walletType: String,
        mnemonicBackedUp: Boolean
    ) {
        val keyBytes = privateKeyHex.toByteArray(Charsets.UTF_8)
        val keyCipher = encryptionManager.newEncryptCipher()
        val (encryptedKey, iv) = encryptionManager.encryptWithCipher(keyCipher, keyBytes)

        val mnemonicWithIv = mnemonic?.let {
            val mnemonicBytes = it.toByteArray(Charsets.UTF_8)
            val mnemonicCipher = encryptionManager.newEncryptCipher()
            val (encMnemonic, mnemonicIv) = encryptionManager.encryptWithCipher(mnemonicCipher, mnemonicBytes)
            mnemonicIv + encMnemonic // 12-byte IV prefix + ciphertext
        }

        val entity = KeyMaterialEntity(
            walletId = walletId,
            encryptedPrivateKey = encryptedKey,
            encryptedMnemonic = mnemonicWithIv,
            iv = iv,
            walletType = walletType,
            mnemonicBackedUp = mnemonicBackedUp,
            updatedAt = System.currentTimeMillis()
        )

        keyMaterialDao.upsert(entity)
    }

    suspend fun readDecryptedKey(walletId: String): DecryptedKeyData? {
        val entity = keyMaterialDao.getByWalletId(walletId) ?: return null

        return try {
            val keyCipher = encryptionManager.newDecryptCipher(entity.iv)
            val keyBytes = encryptionManager.decryptWithCipher(keyCipher, entity.encryptedPrivateKey)
            val privateKeyHex = String(keyBytes, Charsets.UTF_8)

            val mnemonic = entity.encryptedMnemonic?.let { combined ->
                val mnemonicIv = combined.sliceArray(0 until 12)
                val mnemonicCiphertext = combined.sliceArray(12 until combined.size)
                val mnemonicCipher = encryptionManager.newDecryptCipher(mnemonicIv)
                String(
                    encryptionManager.decryptWithCipher(mnemonicCipher, mnemonicCiphertext),
                    Charsets.UTF_8
                )
            }

            DecryptedKeyData(
                privateKeyHex = privateKeyHex,
                mnemonic = mnemonic,
                walletType = entity.walletType,
                mnemonicBackedUp = entity.mnemonicBackedUp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt key material for $walletId", e)
            null
        }
    }

    fun isMigrationComplete(): Boolean {
        return migrationPrefs.getBoolean(KEY_MIGRATION_COMPLETE, false)
    }

    fun markMigrationComplete() {
        migrationPrefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).commit()
    }

    suspend fun deleteKey(walletId: String) {
        keyMaterialDao.delete(walletId)
    }

    suspend fun hasAnyKeys(): Boolean {
        return keyMaterialDao.count() > 0
    }

    companion object {
        private const val TAG = "KeyStoreMigrationHelper"
        private const val KEY_MIGRATION_COMPLETE = "esp_to_room_migration_complete"
    }
}
