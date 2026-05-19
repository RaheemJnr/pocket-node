package com.rjnr.pocketnode.data.wallet

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import javax.inject.Inject
import javax.inject.Singleton
import org.nervos.ckb.utils.Numeric

/**
 * Activity-aware bridge between a ViewModel and the (V1 or V2)
 * key-material store. Lets call sites read a wallet's private key without
 * caring whether the row is on the legacy unrestricted Keystore key (V1)
 * or the auth-bound key (V2); the BiometricPrompt CryptoObject dance is
 * encapsulated here.
 *
 * ## Why a separate class
 *
 * `KeyManager` is `@Singleton` and never sees an `Activity`. Threading
 * `FragmentActivity` through every call site for V2-only code paths was
 * a bigger refactor than the audit fix warranted. This reader is the one
 * boundary between the data layer and the UI layer that needs activity
 * scope. It's intentionally small.
 *
 * ## Status
 *
 * Sub-PR 4 of #213 wires this into [SendViewModel][com.rjnr.pocketnode.ui.screens.send.SendViewModel]
 * as the first user. Sub-PR 5 will migrate mnemonic backup, sub-account
 * derivation, DAO ops, and the security settings export flow to this
 * same reader.
 */
@Singleton
class WalletKeyReader @Inject constructor(
    private val keyMaterialDao: KeyMaterialDao,
    private val keyStoreMigrationHelper: KeyStoreMigrationHelper,
    private val encryptionManager: KeystoreEncryptionManager,
    private val authManager: AuthManager,
) {

    sealed class Result {
        data class Success(val privateKey: ByteArray) : Result()
        /** User cancelled the biometric prompt; no key returned. */
        object Cancelled : Result()
        /** Auth framework reported an error (not user-cancel). */
        data class AuthError(val errorCode: Int, val message: CharSequence) : Result()
        /** Wallet row missing, decrypt failed, or unknown kdfVersion. */
        data class NotAvailable(val reason: String) : Result()
    }

    sealed class MaterialResult {
        data class Success(
            val privateKey: ByteArray,
            val mnemonic: String?,
            val walletType: String,
            val mnemonicBackedUp: Boolean,
        ) : MaterialResult()
        object Cancelled : MaterialResult()
        data class AuthError(val errorCode: Int, val message: CharSequence) : MaterialResult()
        data class NotAvailable(val reason: String) : MaterialResult()
    }

    /**
     * Read the private key for [walletId]. Picks the V1 or V2 code path
     * based on the row's `kdfVersion`. V2 prompts the user via
     * `BiometricPrompt`; V1 returns silently.
     *
     * The Cipher returned by Keystore is single-shot: this method consumes
     * it immediately in `decryptWithCipher` and discards it. Callers must
     * not retain the returned key any longer than needed for the operation
     * at hand (sign, derive sub-account, display mnemonic).
     */
    suspend fun readPrivateKey(
        activity: FragmentActivity,
        walletId: String,
        promptTitle: String,
        promptSubtitle: String,
    ): Result {
        val kdfVersion = keyMaterialDao.getKdfVersion(walletId)
            ?: return Result.NotAvailable("no key_material row for $walletId")

        return when (kdfVersion) {
            1 -> readV1(walletId)
            2 -> readV2(activity, walletId, promptTitle, promptSubtitle)
            else -> Result.NotAvailable("unknown kdfVersion=$kdfVersion")
        }
    }

    /**
     * Like [readPrivateKey] but returns the full key material bundle —
     * private key, mnemonic, walletType, mnemonicBackedUp — in a single
     * V2 cipher operation. Used by call sites that need both pieces (e.g.
     * the seed-phrase reveal screen, or sub-account derivation that needs
     * the parent's mnemonic).
     *
     * V2 cost: exactly one BiometricPrompt regardless of how much of the
     * bundle the caller uses. V1 cost: silent, same as [readPrivateKey].
     */
    suspend fun readKeyMaterial(
        activity: FragmentActivity,
        walletId: String,
        promptTitle: String,
        promptSubtitle: String,
    ): MaterialResult {
        val kdfVersion = keyMaterialDao.getKdfVersion(walletId)
            ?: return MaterialResult.NotAvailable("no key_material row for $walletId")
        return when (kdfVersion) {
            1 -> readMaterialV1(walletId)
            2 -> readMaterialV2(activity, walletId, promptTitle, promptSubtitle)
            else -> MaterialResult.NotAvailable("unknown kdfVersion=$kdfVersion")
        }
    }

    private suspend fun readMaterialV1(walletId: String): MaterialResult {
        val data = keyStoreMigrationHelper.readDecryptedKey(walletId)
            ?: return MaterialResult.NotAvailable("V1 decrypt failed for $walletId")
        return MaterialResult.Success(
            privateKey = Numeric.hexStringToByteArray(data.privateKeyHex),
            mnemonic = data.mnemonic,
            walletType = data.walletType,
            mnemonicBackedUp = data.mnemonicBackedUp,
        )
    }

    private suspend fun readMaterialV2(
        activity: FragmentActivity,
        walletId: String,
        promptTitle: String,
        promptSubtitle: String,
    ): MaterialResult {
        val entity = keyMaterialDao.getByWalletId(walletId)
            ?: return MaterialResult.NotAvailable("no key_material row for $walletId")
        val cipher = encryptionManager.newDecryptCipherV2(entity.iv)
        val authResult = authManager.authenticateForCipher(
            activity = activity,
            cipher = cipher,
            title = promptTitle,
            subtitle = promptSubtitle,
        )
        return when (authResult) {
            is AuthManager.CipherAuthResult.Cancelled -> MaterialResult.Cancelled
            is AuthManager.CipherAuthResult.Error ->
                MaterialResult.AuthError(authResult.errorCode, authResult.errString)
            is AuthManager.CipherAuthResult.Success -> {
                val data = try {
                    keyStoreMigrationHelper.readDecryptedKey(walletId, authResult.cipher)
                } catch (e: Exception) {
                    Log.e(TAG, "V2 material decrypt failed for $walletId", e)
                    null
                } ?: return MaterialResult.NotAvailable("V2 decrypt failed for $walletId")
                MaterialResult.Success(
                    privateKey = Numeric.hexStringToByteArray(data.privateKeyHex),
                    mnemonic = data.mnemonic,
                    walletType = data.walletType,
                    mnemonicBackedUp = data.mnemonicBackedUp,
                )
            }
        }
    }

    private suspend fun readV1(walletId: String): Result {
        val data = keyStoreMigrationHelper.readDecryptedKey(walletId)
            ?: return Result.NotAvailable("V1 decrypt failed for $walletId")
        return Result.Success(Numeric.hexStringToByteArray(data.privateKeyHex))
    }

    private suspend fun readV2(
        activity: FragmentActivity,
        walletId: String,
        promptTitle: String,
        promptSubtitle: String,
    ): Result {
        val entity = keyMaterialDao.getByWalletId(walletId)
            ?: return Result.NotAvailable("no key_material row for $walletId")
        val cipher = encryptionManager.newDecryptCipherV2(entity.iv)
        val authResult = authManager.authenticateForCipher(
            activity = activity,
            cipher = cipher,
            title = promptTitle,
            subtitle = promptSubtitle,
        )
        return when (authResult) {
            is AuthManager.CipherAuthResult.Cancelled -> Result.Cancelled
            is AuthManager.CipherAuthResult.Error ->
                Result.AuthError(authResult.errorCode, authResult.errString)
            is AuthManager.CipherAuthResult.Success -> {
                val data = try {
                    keyStoreMigrationHelper.readDecryptedKey(walletId, authResult.cipher)
                } catch (e: Exception) {
                    Log.e(TAG, "V2 decrypt failed for $walletId", e)
                    null
                } ?: return Result.NotAvailable("V2 decrypt failed for $walletId")
                Result.Success(Numeric.hexStringToByteArray(data.privateKeyHex))
            }
        }
    }

    companion object {
        private const val TAG = "WalletKeyReader"
    }
}
