package com.rjnr.pocketnode.data.migration

import android.content.SharedPreferences
import android.util.Log
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import javax.crypto.Cipher
import kotlinx.serialization.json.Json

data class DecryptedKeyData(
    val privateKeyHex: String,
    val mnemonic: String?,
    val walletType: String,
    val mnemonicBackedUp: Boolean
)

/**
 * Thrown when [KeyStoreMigrationHelper.readDecryptedKey] is asked to read
 * a V2 (auth-bound) row without an authenticated Cipher. The caller must
 * obtain a Cipher via [KeystoreEncryptionManager.newDecryptCipherV2],
 * pass it through `AuthManager.authenticateForCipher`, and retry with the
 * returned overload that accepts a Cipher.
 *
 * Surfaced as an explicit exception (rather than a null return) so call
 * sites that haven't yet migrated to the V2-aware overload fail loudly
 * instead of silently treating the wallet as non-existent.
 */
class V2KeyMaterialRequiresAuthException(walletId: String) :
    IllegalStateException("walletId=$walletId is on V2 (kdfVersion=2); caller must supply an authenticated Cipher")

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

    /**
     * Read key material for a wallet. V1 (kdfVersion=1) rows decrypt
     * silently under the legacy unrestricted Keystore key. V2 rows throw
     * [V2KeyMaterialRequiresAuthException] — callers must use the overload
     * that accepts an authenticated Cipher.
     *
     * Until sub-PR 5 of #213 wires every call site to the V2 path,
     * production wallets remain on V1 (the migration is not yet invoked
     * from app code), so this method behaves exactly like v1.6.x for live
     * users.
     */
    suspend fun readDecryptedKey(walletId: String): DecryptedKeyData? {
        val entity = keyMaterialDao.getByWalletId(walletId) ?: return null
        return when (entity.kdfVersion) {
            1 -> decryptV1Row(entity)
            2 -> throw V2KeyMaterialRequiresAuthException(walletId)
            else -> {
                Log.e(TAG, "Unknown kdfVersion=${entity.kdfVersion} for walletId=$walletId")
                null
            }
        }
    }

    /**
     * Read key material from a V2 row. The caller has already authenticated
     * [v2DecryptCipher] via `AuthManager.authenticateForCipher` — Keystore
     * releases the key for exactly one `doFinal` call, which is exhausted
     * here. The bundled JSON format is established by
     * [KeystoreV2MigrationHelper.WalletKeyBundle].
     *
     * V1 rows are still readable through this overload (falls through to
     * the V1 path; the V2 cipher is ignored) so that a caller that doesn't
     * know which version a wallet is on can pass a V2 cipher unconditionally
     * during migration. Post-migration, every row is V2 and this branch
     * never fires.
     */
    suspend fun readDecryptedKey(walletId: String, v2DecryptCipher: Cipher): DecryptedKeyData? {
        val entity = keyMaterialDao.getByWalletId(walletId) ?: return null
        return when (entity.kdfVersion) {
            1 -> decryptV1Row(entity)
            2 -> decryptV2Row(entity, v2DecryptCipher)
            else -> {
                Log.e(TAG, "Unknown kdfVersion=${entity.kdfVersion} for walletId=$walletId")
                null
            }
        }
    }

    private fun decryptV1Row(entity: KeyMaterialEntity): DecryptedKeyData? {
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
            Log.e(TAG, "Failed to decrypt V1 key material for ${entity.walletId}", e)
            null
        }
    }

    private fun decryptV2Row(entity: KeyMaterialEntity, v2Cipher: Cipher): DecryptedKeyData? {
        return try {
            val bundleBytes = encryptionManager.decryptWithCipher(v2Cipher, entity.encryptedPrivateKey)
            val bundle = v2Json.decodeFromString(
                WalletKeyBundle.serializer(),
                String(bundleBytes, Charsets.UTF_8)
            )
            DecryptedKeyData(
                privateKeyHex = bundle.privateKeyHex,
                mnemonic = bundle.mnemonic,
                walletType = entity.walletType,
                mnemonicBackedUp = entity.mnemonicBackedUp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt V2 key material for ${entity.walletId}", e)
            null
        }
    }

    private val v2Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
