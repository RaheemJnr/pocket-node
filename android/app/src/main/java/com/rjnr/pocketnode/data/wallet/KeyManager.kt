package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import org.nervos.ckb.crypto.Blake2b
import org.nervos.ckb.crypto.secp256k1.ECKeyPair
import org.nervos.ckb.crypto.secp256k1.Sign
import org.nervos.ckb.utils.Numeric
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mnemonicManager: MnemonicManager
) {
    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

    @VisibleForTesting
    internal var keyBackupManager: KeyBackupManager? = null

    @VisibleForTesting
    internal var keyStoreMigrationHelper: KeyStoreMigrationHelper? = null

    // AuthManager is the single source of truth for the session PIN used to encrypt
    // KeyBackupManager material. Wired via provideKeyManager in AppModule (the
    // @Provides factory — Hilt does not fire @Inject setters on @Provides builds).
    @VisibleForTesting
    internal var authManager: AuthManager? = null

    // Fallback PIN holder, only used by unit tests that don't wire an AuthManager.
    // Prefer authManager.getSessionPin() in production.
    @VisibleForTesting
    internal var sessionPin: CharArray? = null

    fun setSessionPin(pin: CharArray) {
        sessionPin?.let { java.util.Arrays.fill(it, '\u0000') }
        sessionPin = pin.copyOf()
    }

    fun clearSessionPin() {
        sessionPin?.let { java.util.Arrays.fill(it, '\u0000') }
        sessionPin = null
    }

    /**
     * Pure-crypto plaintext bundle producer. Used by the new V2 write path
     * (see [WalletKeyWriter]). The caller is responsible for persistence.
     */
    fun encodePlaintextBundle(
        privateKey: ByteArray,
        mnemonic: List<String>?,
    ): WalletKeyBundle {
        return WalletKeyBundle(
            privateKeyHex = privateKey.joinToString("") { "%02x".format(it) },
            mnemonic = mnemonic?.joinToString(" "),
        )
    }

    @Inject
    fun setBackupManager(backupManager: KeyBackupManager) {
        this.keyBackupManager = backupManager
    }

    @Inject
    fun setMigrationHelper(helper: KeyStoreMigrationHelper) {
        this.keyStoreMigrationHelper = helper
    }

    // @Deprecated ESP fallback — remove after one release cycle (v1.6.0+)
    private var walletResetDueToCorruption = false

    @Deprecated("ESP fallback — remove after one release cycle")
    fun wasResetDueToCorruption(): Boolean = walletResetDueToCorruption

    @Deprecated("ESP fallback — remove after one release cycle")
    fun resetCorruptionFlag() {
        walletResetDueToCorruption = false
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private val prefs: SharedPreferences
        get() = testPrefs ?: encryptedPrefs

    @Deprecated("ESP fallback — remove after one release cycle")
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs(useStrongBox = true)
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox-backed prefs failed, trying without StrongBox", e)
            // NEVER delete the prefs file — it may contain the user's only copy of
            // their private key and mnemonic. Try without StrongBox instead.
            try {
                createEncryptedPrefs(useStrongBox = false)
            } catch (e2: Exception) {
                // Both attempts failed — the keystore or prefs file is genuinely corrupted.
                // Surface the error so the user sees a warning. Do NOT delete the file.
                Log.e(TAG, "EncryptedSharedPreferences completely unreadable", e2)
                walletResetDueToCorruption = true
                // Last resort: try StrongBox one more time (sometimes transient)
                createEncryptedPrefs(useStrongBox = true)
            }
        }
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private fun createEncryptedPrefs(useStrongBox: Boolean = true): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(useStrongBox)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "ckb_wallet_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun hasWallet(): Boolean {
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val hasKeys = helper.hasAnyKeys()
            if (hasKeys) return true
        }
        return prefs.contains(KEY_PRIVATE_KEY)
    }

    suspend fun getMnemonic(): List<String>? {
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val data = helper.readDecryptedKey("default")
            if (data != null) {
                return data.mnemonic?.split(" ")
            }
        }
        // Fallback to ESP
        val joined = prefs.getString(KEY_MNEMONIC, null) ?: return null
        return joined.split(" ")
    }

    suspend fun getWalletType(): String {
        // Flag-only read: walletType is a plaintext column. Decrypting the
        // key bundle here would throw on V2 rows (post-#275 keystore
        // migration) and crash the auth/nav path.
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.getWalletTypeFlag("default")?.let { return it }
        }
        // Fallback to ESP
        return prefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY
    }

    suspend fun hasMnemonicBackup(): Boolean {
        // Flag-only read; see getWalletType for the V2 reasoning.
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.getMnemonicBackedUpFlag("default")?.let { return it }
        }
        // Fallback to ESP
        return prefs.getBoolean(KEY_MNEMONIC_BACKED_UP, false)
    }

    suspend fun setMnemonicBackedUp(backedUp: Boolean) {
        // Flip the plaintext flag in Room without re-encrypting the
        // bundle — V2 rows cannot be decrypted without an authenticated
        // Cipher, and this call site has none. The PIN backup blob keeps
        // its previous mnemonicBackedUp value until the next mnemonic
        // reveal (which carries a fresh Cipher and refreshes the blob).
        val helper = keyStoreMigrationHelper
        if (helper != null && helper.setMnemonicBackedUpFlag("default", backedUp)) {
            return
        }
        // ESP fallback
        prefs.edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).commit()
        val pk = prefs.getString(KEY_PRIVATE_KEY, null) ?: return
        val espWalletType = prefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY
        writeBackupIfPinAvailable("default") {
            KeyMaterial(
                privateKey = pk,
                mnemonic = prefs.getString(KEY_MNEMONIC, null),
                walletType = espWalletType,
                mnemonicBackedUp = backedUp
            )
        }
    }

    // -- Shared methods --

    suspend fun getWalletInfo(): WalletInfo {
        val keyPair = getKeyPair()
        val publicKey = keyPair.getEncodedPublicKey(true) // compressed
        val script = deriveLockScript(publicKey)
        val testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET)
        val mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET)

        return WalletInfo(
            publicKey = Numeric.toHexString(publicKey),
            script = script,
            testnetAddress = testnetAddress,
            mainnetAddress = mainnetAddress
        )
    }

    suspend fun getPrivateKey(): ByteArray {
        // Try Room first
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val data = helper.readDecryptedKey("default")
            if (data != null) {
                return Numeric.hexStringToByteArray(data.privateKeyHex)
            }
        }
        // Fallback to ESP
        val hex = prefs.getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found")
        return Numeric.hexStringToByteArray(hex)
    }

    suspend fun getKeyPair(): ECKeyPair {
        // Try Room first
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val data = helper.readDecryptedKey("default")
            if (data != null) {
                return ECKeyPair.create(BigInteger(data.privateKeyHex, 16))
            }
        }
        // Fallback to ESP
        val hex = prefs.getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found")
        val privateKey = BigInteger(hex, 16)
        return ECKeyPair.create(privateKey)
    }

    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        return keyPair.getEncodedPublicKey(true)
    }

    fun deriveLockScript(publicKey: ByteArray): Script {
        val pubKeyHash = Blake2b.digest(publicKey)
        val args = pubKeyHash.copyOfRange(0, 20)

        return Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = Numeric.toHexString(args)
        )
    }

    /**
     * Recover a wallet's lock script from a cached CKB address without
     * touching key material. Used by init/sync paths that previously read
     * the private key just to compute the lock args (those reads would
     * now require a BiometricPrompt for V2 wallets and crash app startup).
     *
     * The bech32 address encodes the same args+codeHash+hashType triple
     * that [deriveLockScript] would produce, so the round-trip is exact.
     */
    fun deriveLockScriptFromAddress(address: String): Script {
        return AddressUtils.decode(address)
    }

    /**
     * Derive [WalletInfo] from a Room [com.rjnr.pocketnode.data.database.entity.WalletEntity]'s
     * cached addresses. No key access required — safe to call for V2
     * wallets during app boot, wallet switch, and multi-wallet sync setup.
     *
     * `publicKey` is returned as an empty string because the address-only
     * path cannot recover the SECP256K1 public key. Callers that need the
     * raw public key (currently none — see #213 sub-PR 5 audit) must
     * obtain the private key explicitly via [WalletKeyReader].
     */
    fun deriveWalletInfoFromEntity(
        wallet: com.rjnr.pocketnode.data.database.entity.WalletEntity
    ): WalletInfo {
        val address = wallet.testnetAddress.ifBlank { wallet.mainnetAddress }
        val script = deriveLockScriptFromAddress(address)
        return WalletInfo(
            publicKey = "",
            script = script,
            testnetAddress = wallet.testnetAddress,
            mainnetAddress = wallet.mainnetAddress,
        )
    }

    suspend fun sign(message: ByteArray): ByteArray {
        val keyPair = getKeyPair()
        val signatureData = Sign.signMessage(message, keyPair)
        return signatureData.signature
    }

    suspend fun deleteWallet() {
        keyBackupManager?.deleteBackup("default")
        // Delete from Room
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.deleteKey("default")
        }
        // Also clear ESP (for pre-migration state)
        prefs.edit()
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_MNEMONIC)
            .remove(KEY_MNEMONIC_BACKED_UP)
            .remove(KEY_WALLET_TYPE)
            .apply()
    }

    // -- Wallet-scoped key storage (multi-wallet support) --

    suspend fun storeKeysForWallet(walletId: String, privateKey: ByteArray, mnemonic: List<String>?) {
        val hex = privateKey.joinToString("") { "%02x".format(it) }
        val walletType = if (mnemonic != null) WALLET_TYPE_MNEMONIC else WALLET_TYPE_RAW_KEY

        // Write to Room (primary) — inlined writeToRoom (deleted in #289 chunk 3.3)
        keyStoreMigrationHelper?.migrateWallet(walletId, hex, mnemonic?.joinToString(" "), walletType, false)

        // Write to PIN backup (secondary)
        writeBackupIfPinAvailable(walletId) {
            KeyMaterial(
                privateKey = hex,
                mnemonic = mnemonic?.joinToString(" "),
                walletType = walletType,
                mnemonicBackedUp = false
            )
        }
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private fun getWalletPrefs(walletId: String): SharedPreferences {
        val fileName = "ckb_wallet_keys_$walletId"
        return try {
            createEncryptedPrefsForWallet(fileName, useStrongBox = true)
        } catch (e: Exception) {
            Log.w(TAG, "Wallet prefs ($walletId) StrongBox failed, trying without", e)
            // NEVER delete — may contain user's only key material
            try {
                createEncryptedPrefsForWallet(fileName, useStrongBox = false)
            } catch (e2: Exception) {
                Log.e(TAG, "Wallet prefs ($walletId) completely unreadable", e2)
                createEncryptedPrefsForWallet(fileName, useStrongBox = true)
            }
        }
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private fun createEncryptedPrefsForWallet(fileName: String, useStrongBox: Boolean): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply { if (useStrongBox) setRequestStrongBoxBacked(true) }
            .build()
        return EncryptedSharedPreferences.create(
            context, fileName, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun getMnemonicForWallet(walletId: String): List<String>? {
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val data = helper.readDecryptedKey(walletId)
            if (data != null) {
                return data.mnemonic?.split(" ")
            }
        }
        // Fallback to ESP
        val joined = getWalletPrefs(walletId).getString(KEY_MNEMONIC, null) ?: return null
        return joined.split(" ")
    }

    suspend fun getPrivateKeyForWallet(walletId: String): ByteArray? {
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val data = helper.readDecryptedKey(walletId)
            if (data != null) {
                return Numeric.hexStringToByteArray(data.privateKeyHex)
            }
        }
        // Fallback to ESP
        val hex = getWalletPrefs(walletId).getString(KEY_PRIVATE_KEY, null) ?: return null
        return Numeric.hexStringToByteArray(hex)
    }

    /**
     * Derive WalletInfo (public key, lock script, addresses) from a raw private key.
     * Used when switching wallets — the active wallet's private key is loaded from
     * its per-wallet EncryptedSharedPreferences and info is derived without touching
     * the legacy "default" prefs.
     */
    fun deriveWalletInfo(privateKey: ByteArray): WalletInfo {
        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        val publicKey = keyPair.getEncodedPublicKey(true)
        val script = deriveLockScript(publicKey)
        val testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET)
        val mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET)

        return WalletInfo(
            publicKey = Numeric.toHexString(publicKey),
            script = script,
            testnetAddress = testnetAddress,
            mainnetAddress = mainnetAddress
        )
    }

    suspend fun setMnemonicBackedUpForWallet(walletId: String, backedUp: Boolean) {
        // See [setMnemonicBackedUp] for the V2 flag-only rationale.
        val helper = keyStoreMigrationHelper
        if (helper != null && helper.setMnemonicBackedUpFlag(walletId, backedUp)) {
            return
        }
        // ESP fallback
        getWalletPrefs(walletId).edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).commit()
        writeBackupIfPinAvailable(walletId) {
            val walletPrefs = getWalletPrefs(walletId)
            KeyMaterial(
                privateKey = walletPrefs.getString(KEY_PRIVATE_KEY, null) ?: "",
                mnemonic = walletPrefs.getString(KEY_MNEMONIC, null),
                walletType = walletPrefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY,
                mnemonicBackedUp = backedUp
            )
        }
    }

    suspend fun hasMnemonicBackupForWallet(walletId: String): Boolean {
        // Flag-only read; see [getWalletType] for the V2 reasoning. The
        // old code decrypted the bundle just to pull this boolean, which
        // threw V2KeyMaterialRequiresAuthException on every launch
        // post-keystore-migration.
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.getMnemonicBackedUpFlag(walletId)?.let { return it }
        }
        // Fallback to ESP
        return getWalletPrefs(walletId).getBoolean(KEY_MNEMONIC_BACKED_UP, false)
    }

    suspend fun deleteWalletKeys(walletId: String) {
        keyBackupManager?.deleteBackup(walletId)
        // Delete from Room
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.deleteKey(walletId)
        }
        // Also clear ESP (for pre-migration state)
        getWalletPrefs(walletId).edit()
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_MNEMONIC)
            .remove(KEY_MNEMONIC_BACKED_UP)
            .remove(KEY_WALLET_TYPE)
            .commit()
    }

    /**
     * One-time migration: read all wallet keys from ESP, encrypt and store in Room.
     * Verifies round-trip for each wallet. Only marks complete when ALL wallets succeed.
     * Safe to call multiple times — no-op if already complete.
     */
    suspend fun migrateEspToRoomIfNeeded(walletDao: com.rjnr.pocketnode.data.database.dao.WalletDao) {
        val helper = keyStoreMigrationHelper ?: return
        if (helper.isMigrationComplete()) return

        try {
            val wallets = walletDao.getAll()
            for (wallet in wallets) {
                // Skip if already migrated (idempotent)
                if (helper.readDecryptedKey(wallet.walletId) != null) continue

                // Skip Room-only wallets whose ESP file doesn't exist on disk — calling
                // getWalletPrefs for a non-existent file lazily creates an empty ESP file
                // that deleteEspFilesIfSafe would then have to clean up.
                val espFile = java.io.File(
                    context.filesDir.parent,
                    "shared_prefs/ckb_wallet_keys_${wallet.walletId}.xml"
                )
                if (!espFile.exists()) continue

                val privKeyHex = try {
                    getPrivateKeyForWallet(wallet.walletId)
                        ?.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot read ESP key for ${wallet.walletId}, skipping", e)
                    continue
                } ?: continue

                // Only consult ESP prefs now that we've confirmed ESP was authoritative.
                val espPrefs = getWalletPrefs(wallet.walletId)
                val mnemonic = getMnemonicForWallet(wallet.walletId)?.joinToString(" ")
                // ESP stored the authoritative walletType via savePrivateKey/storeKeysForWallet.
                // Fall back to the mnemonic-presence inference only when the pref is absent
                // (e.g. corrupted prefs), so future wallet types round-trip unchanged.
                val walletType = espPrefs.getString(KEY_WALLET_TYPE, null)
                    ?: if (mnemonic != null) WALLET_TYPE_MNEMONIC else WALLET_TYPE_RAW_KEY
                val backed = hasMnemonicBackupForWallet(wallet.walletId)

                helper.migrateWallet(wallet.walletId, privKeyHex, mnemonic, walletType, backed)

                // Verify round-trip
                val check = helper.readDecryptedKey(wallet.walletId)
                if (check == null || check.privateKeyHex != privKeyHex) {
                    Log.e(TAG, "Round-trip verification failed for ${wallet.walletId}")
                    return // Abort — don't mark complete, retry next launch
                }
            }

            helper.markMigrationComplete()
            Log.i(TAG, "ESP to Room migration complete for ${wallets.size} wallets")
        } catch (e: Exception) {
            Log.e(TAG, "ESP to Room migration failed", e)
        }
    }

    /**
     * Delete ESP shared_prefs files after successful Room migration.
     * Only runs if migration is marked complete. Safe to call multiple times.
     */
    fun deleteEspFilesIfSafe() {
        val helper = keyStoreMigrationHelper ?: return
        if (!helper.isMigrationComplete()) return

        try {
            context.deleteSharedPreferences("ckb_wallet_keys")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete global ESP file", e)
        }

        val prefsDir = java.io.File(context.filesDir.parent, "shared_prefs")
        prefsDir.listFiles()
            ?.filter { it.name.startsWith("ckb_wallet_keys_") }
            ?.forEach { file ->
                try { file.delete() } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete ESP file: ${file.name}", e)
                }
            }

        Log.i(TAG, "ESP files deleted after successful Room migration")
    }

private fun writeBackupIfPinAvailable(walletId: String, buildMaterial: () -> KeyMaterial) {
        val pin = authManager?.getSessionPin() ?: sessionPin ?: return
        val manager = keyBackupManager ?: return
        try {
            // Hand KeyBackupManager its own copy — if writeBackup defensively zeroes
            // the PIN array after use, our cached session PIN must stay intact for
            // later backup writes in the same session.
            manager.writeBackup(walletId, buildMaterial(), pin.copyOf())
        } catch (e: Exception) {
            Log.w(TAG, "Backup write failed for $walletId", e)
        }
    }

    companion object {
        private const val TAG = "KeyManager"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_MNEMONIC = "mnemonic_words"
        private const val KEY_MNEMONIC_BACKED_UP = "mnemonic_backed_up"
        private const val KEY_WALLET_TYPE = "wallet_type"
        const val WALLET_TYPE_MNEMONIC = "mnemonic"
        const val WALLET_TYPE_RAW_KEY = "raw_key"
    }
}

data class WalletInfo(
    val publicKey: String,
    val script: Script,
    val testnetAddress: String,
    val mainnetAddress: String
)
