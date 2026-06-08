package com.rjnr.pocketnode.data.migration

import android.content.SharedPreferences
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import javax.crypto.Cipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The plaintext bundle written under the V2 (auth-bound) Keystore key.
 *
 * V1 stored `privateKey` and `mnemonic` as two separate AES-GCM ciphertexts
 * with two separate IVs. V2 stores them as a single JSON blob inside one
 * AES-GCM ciphertext, so each wallet's migration is exactly one
 * BiometricPrompt + one `Cipher.doFinal` per direction. Reduces the prompt
 * count from 2N → N during migration (where N = number of wallets).
 */
@Serializable
data class WalletKeyBundle(
    val privateKeyHex: String,
    val mnemonic: String? = null,
)

/**
 * Orchestrates the v1.6.x → v1.7.0 migration that re-encrypts every wallet's
 * key material under the auth-bound V2 Keystore key (#213).
 *
 * ## Contract
 *
 * - The caller drives a BiometricPrompt per wallet and passes the
 *   authenticated `Cipher` into [migrateWallet]. The helper does NOT
 *   prompt; it only encrypts/decrypts and writes to Room.
 * - V1 decrypt requires no auth (the V1 keystore key is unrestricted).
 *   The helper acquires the V1 decrypt Cipher itself.
 * - V2 encrypt requires auth. The caller passes in a Cipher already
 *   unlocked by `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))`.
 * - Each [migrateWallet] call is atomic at the Room row level. A crash
 *   between rows leaves the migrated rows on V2 (kdfVersion=2) and the
 *   unmigrated rows on V1 (kdfVersion=1). Recovery is idempotent: re-run
 *   the migration over the still-V1 wallets returned by [pendingWalletIds].
 * - After all wallets are V2, the caller invokes [finalize] which deletes
 *   the V1 Keystore key and sets the migration-complete flag in prefs.
 *
 * ## Idempotence
 *
 * Calling [migrateWallet] for an already-V2 wallet returns success without
 * doing any crypto. This lets the caller blindly iterate without first
 * filtering out completed rows.
 */
class KeystoreV2MigrationHelper(
    private val keyMaterialDao: KeyMaterialDao,
    private val encryptionManager: KeystoreEncryptionManager,
    private val prefs: SharedPreferences,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** True if every wallet has been migrated and [finalize] has run. */
    fun isMigrationComplete(): Boolean {
        return prefs.getBoolean(KEY_MIGRATION_V2_COMPLETE, false)
    }

    /** Walletids that still need to migrate from V1 to V2. */
    suspend fun pendingWalletIds(): List<String> {
        return keyMaterialDao.getV1WalletIds()
    }

    /** Re-encrypt one wallet's key material under the authenticated V2 Cipher. */
    suspend fun migrateWallet(walletId: String, v2EncryptCipher: Cipher): Result<Unit> {
        return runCatching {
            val entity = keyMaterialDao.getByWalletId(walletId)
                ?: throw IllegalStateException("No key_material row for walletId=$walletId")

            if (entity.kdfVersion == V2_VERSION) {
                Log.i(TAG, "Wallet $walletId already on V2; nothing to do")
                return@runCatching
            }
            if (entity.kdfVersion != V1_VERSION) {
                throw IllegalStateException(
                    "Unknown kdfVersion ${entity.kdfVersion} for walletId=$walletId; refusing to migrate"
                )
            }

            // Step 1: decrypt V1 ciphertexts. No auth needed; V1 key is unrestricted.
            val privateKeyHex = decryptV1PrivateKey(entity.encryptedPrivateKey, entity.iv)
            val mnemonic = entity.encryptedMnemonic?.let { combined ->
                decryptV1Mnemonic(combined)
            }

            // Step 2: bundle the plaintext so one V2 encrypt covers both pieces.
            val bundle = WalletKeyBundle(privateKeyHex = privateKeyHex, mnemonic = mnemonic)
            val bundleBytes = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)

            // Step 3: encrypt the bundle under V2. The Cipher was unlocked by the
            // caller via BiometricPrompt; doFinal here consumes that auth ticket.
            val (encryptedBundle, newIv) = encryptionManager.encryptWithCipher(
                v2EncryptCipher,
                bundleBytes
            )

            // Step 4: atomic row swap. Room.upsert runs in a transaction; either
            // both the ciphertext and the kdfVersion=2 land together or neither
            // does. A crash here at worst leaves the wallet on V1 for retry.
            val migrated = entity.copy(
                encryptedPrivateKey = encryptedBundle,
                encryptedMnemonic = null,
                iv = newIv,
                kdfVersion = V2_VERSION,
                updatedAt = nowProvider()
            )
            keyMaterialDao.upsert(migrated)

            Log.i(TAG, "Migrated wallet $walletId from V1 to V2")
        }
    }

    /**
     * Write a brand-new wallet row directly at kdfVersion=2. The Cipher must
     * have been unlocked via BiometricPrompt by the caller. Used by `WalletKeyWriter`
     * on the new-wallet creation path so that wallets created post-v1.7.0 land
     * on the auth-bound V2 key from the start rather than transiting V1 (#289).
     *
     * Refuses if a key_material row already exists for [walletId]; callers must
     * not silently overwrite an existing wallet's key material. Use the V1→V2
     * migration path for legacy wallets.
     */
    suspend fun writeNewV2Row(
        walletId: String,
        bundle: WalletKeyBundle,
        v2EncryptCipher: Cipher,
        walletType: String,
        mnemonicBackedUp: Boolean,
    ): Result<Unit> {
        return runCatching {
            // Soft early-exit: keeps the user-friendly exception fast on the
            // single-threaded happy path. The atomic guard is `insertOrAbort`
            // below — between this read and that write a concurrent inserter
            // could land a row, in which case the SQLite UNIQUE constraint on
            // `walletId` aborts the insert and we translate to the same
            // IllegalStateException for callers (#289 polish).
            val existing = keyMaterialDao.getByWalletId(walletId)
            if (existing != null) {
                throw IllegalStateException("walletId=$walletId already exists in key_material; refuse to overwrite")
            }
            val bundleBytes = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)
            val (encryptedBundle, newIv) = encryptionManager.encryptWithCipher(v2EncryptCipher, bundleBytes)
            val entity = KeyMaterialEntity(
                walletId = walletId,
                encryptedPrivateKey = encryptedBundle,
                encryptedMnemonic = null,
                iv = newIv,
                walletType = walletType,
                mnemonicBackedUp = mnemonicBackedUp,
                updatedAt = nowProvider(),
                kdfVersion = V2_VERSION,
            )
            try {
                keyMaterialDao.insertOrAbort(entity)
            } catch (e: SQLiteConstraintException) {
                throw IllegalStateException(
                    "walletId=$walletId already exists in key_material; refuse to overwrite",
                    e
                )
            }
            Log.i(TAG, "Wrote new V2 wallet row for $walletId")
        }
    }

    /**
     * Like [migrateWallet] but also returns the plaintext bundle to the
     * caller in one BiometricPrompt cycle.
     *
     * Use case: the WalletSettings "View seed phrase" flow on a V1
     * wallet needs to (a) get the mnemonic to the UI and (b) leave the
     * row on V2 so future reveals are V2-gated. The naive approach is
     * two prompts (migrate, then read). This variant collapses to one:
     * the V1 plaintext we decrypt during migration is the same
     * plaintext the caller wants to display, so we hand it back inside
     * the same Result.
     *
     * The plaintext lives only on the caller's stack — the [Result]
     * payload is not logged or persisted by this method.
     */
    suspend fun migrateWalletAndExtract(
        walletId: String,
        v2EncryptCipher: Cipher,
    ): Result<WalletKeyBundle> {
        return runCatching {
            val entity = keyMaterialDao.getByWalletId(walletId)
                ?: throw IllegalStateException("No key_material row for walletId=$walletId")

            if (entity.kdfVersion == V2_VERSION) {
                throw IllegalStateException(
                    "walletId=$walletId is already on V2; use readV2Bundle instead"
                )
            }
            if (entity.kdfVersion != V1_VERSION) {
                throw IllegalStateException(
                    "Unknown kdfVersion ${entity.kdfVersion} for walletId=$walletId; refusing to migrate"
                )
            }

            val privateKeyHex = decryptV1PrivateKey(entity.encryptedPrivateKey, entity.iv)
            val mnemonic = entity.encryptedMnemonic?.let { combined ->
                decryptV1Mnemonic(combined)
            }

            val bundle = WalletKeyBundle(privateKeyHex = privateKeyHex, mnemonic = mnemonic)
            val bundleBytes = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)

            val (encryptedBundle, newIv) = encryptionManager.encryptWithCipher(
                v2EncryptCipher,
                bundleBytes
            )

            val migrated = entity.copy(
                encryptedPrivateKey = encryptedBundle,
                encryptedMnemonic = null,
                iv = newIv,
                kdfVersion = V2_VERSION,
                updatedAt = nowProvider()
            )
            keyMaterialDao.upsert(migrated)

            Log.i(TAG, "Migrated wallet $walletId V1→V2 (extracted plaintext for caller)")
            bundle
        }
    }

    /**
     * After every wallet has been migrated, delete the V1 Keystore key and
     * record the migration-complete flag. Safe to call only when
     * [pendingWalletIds] returns an empty list.
     */
    suspend fun finalize(): Result<Unit> {
        return runCatching {
            val pending = pendingWalletIds()
            if (pending.isNotEmpty()) {
                throw IllegalStateException(
                    "Refusing to finalize migration: ${pending.size} wallets still on V1 ($pending)"
                )
            }
            encryptionManager.deleteV1Key()
            prefs.edit().putBoolean(KEY_MIGRATION_V2_COMPLETE, true).apply()
            Log.i(TAG, "Keystore V2 migration finalized")
        }
    }

    /**
     * Read a V2-encrypted wallet bundle. Used by callers that already know the
     * row is on V2 (kdfVersion=2). The Cipher must be a decrypt-mode Cipher
     * acquired via [KeystoreEncryptionManager.newDecryptCipherV2] and unlocked
     * via BiometricPrompt.
     */
    suspend fun readV2Bundle(walletId: String, v2DecryptCipher: Cipher): WalletKeyBundle? {
        val entity = keyMaterialDao.getByWalletId(walletId) ?: return null
        if (entity.kdfVersion != V2_VERSION) {
            Log.e(TAG, "readV2Bundle: wallet $walletId is not on V2 (kdfVersion=${entity.kdfVersion})")
            return null
        }
        val bundleBytes = encryptionManager.decryptWithCipher(v2DecryptCipher, entity.encryptedPrivateKey)
        return json.decodeFromString(WalletKeyBundle.serializer(), String(bundleBytes, Charsets.UTF_8))
    }

    private fun decryptV1PrivateKey(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = encryptionManager.newDecryptCipher(iv)
        val plaintext = encryptionManager.decryptWithCipher(cipher, ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun decryptV1Mnemonic(combined: ByteArray): String {
        val mnemonicIv = combined.sliceArray(0 until 12)
        val mnemonicCiphertext = combined.sliceArray(12 until combined.size)
        val cipher = encryptionManager.newDecryptCipher(mnemonicIv)
        return String(
            encryptionManager.decryptWithCipher(cipher, mnemonicCiphertext),
            Charsets.UTF_8
        )
    }

    companion object {
        private const val TAG = "KeystoreV2Migration"
        internal const val KEY_MIGRATION_V2_COMPLETE = "keystore_v2_migration_complete"
        const val V1_VERSION = 1
        const val V2_VERSION = 2
    }
}
