package com.rjnr.pocketnode.data.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.migration.DecryptedKeyData
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import javax.inject.Inject
import javax.inject.Singleton
import org.nervos.ckb.utils.Numeric
import kotlinx.coroutines.launch

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
    private val keyBackupManager: KeyBackupManager,
) {

    sealed class Result {
        data class Success(val privateKey: ByteArray) : Result()
        /** User cancelled the biometric prompt; no key returned. */
        object Cancelled : Result()
        /** Auth framework reported an error (not user-cancel). */
        data class AuthError(val errorCode: Int, val message: CharSequence) : Result()
        /** Wallet row missing, decrypt failed, or unknown kdfVersion. */
        data class NotAvailable(val reason: String) : Result()
        /**
         * The V2 Keystore key has been invalidated because the user changed
         * biometric enrollment (added/removed a fingerprint or face).
         * Recovery requires re-importing the wallet from the recovery
         * phrase — the encrypted bundle on disk is unrecoverable.
         */
        object KeyInvalidated : Result()
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
        object KeyInvalidated : MaterialResult()
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
        val cipher = try {
            encryptionManager.newDecryptCipherV2(entity.iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "V2 key invalidated by biometric enrollment change for $walletId")
            return MaterialResult.KeyInvalidated
        } catch (e: IllegalStateException) {
            // Android Keystore throws this when getOrCreateKeystoreKeyV2 is
            // asked to mint a key on a device with no secure lock. Should
            // not normally happen on the V2 read side — the V2 key exists
            // already if a V2 row was ever written. Defensive catch so a
            // post-restore or post-keystore-wipe state degrades to
            // KeyInvalidated (re-import recovery) instead of crashing.
            if (e.message?.contains("Secure lock screen", ignoreCase = true) == true) {
                Log.w(TAG, "V2 key missing and cannot be recreated (no secure lock) for $walletId", e)
                return MaterialResult.KeyInvalidated
            }
            throw e
        }
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
                tryOpportunisticBackup(walletId, data)
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
        val cipher = try {
            encryptionManager.newDecryptCipherV2(entity.iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // User changed biometric enrollment — the V2 key was wiped by
            // Keystore (setInvalidatedByBiometricEnrollment=true). The
            // ciphertext on disk is unrecoverable; caller must surface a
            // re-import flow to the user.
            Log.w(TAG, "V2 key invalidated by biometric enrollment change for $walletId")
            return Result.KeyInvalidated
        } catch (e: IllegalStateException) {
            // Defense in depth: V2 key missing AND no secure lock to
            // recreate it. Same degraded path as KPIE — re-import recovers.
            if (e.message?.contains("Secure lock screen", ignoreCase = true) == true) {
                Log.w(TAG, "V2 key missing and cannot be recreated (no secure lock) for $walletId", e)
                return Result.KeyInvalidated
            }
            throw e
        }
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
                tryOpportunisticBackup(walletId, data)
                Result.Success(Numeric.hexStringToByteArray(data.privateKeyHex))
            }
        }
    }

    /**
     * Lazy PIN-encrypted backup population for V2 wallets (#295).
     *
     * `SecuritySettingsViewModel.onPinCreated` silently skips V2 wallets in
     * its post-PIN-setup population loop because writing a backup requires
     * a biometric-authed read. As a result, V2 wallets created on v1.7.x
     * have no PIN-recoverable backup blob until the user explicitly reveals
     * their seed phrase. This means a user who creates a wallet, sets a
     * PIN, and never reveals the seed has nothing to fall back on for PIN
     * recovery.
     *
     * Fix: any time a V2 read completes successfully, we already hold
     * the full plaintext material under a biometric prompt the user just
     * authed — write the backup blob opportunistically if it does not
     * already exist. Requires a cached session PIN ([AuthManager.getSessionPin]);
     * skipped silently if the user is biometric-only this session. Failures
     * are logged and swallowed — backup population is defense-in-depth, not
     * blocking the caller.
     */
    private fun tryOpportunisticBackup(
        walletId: String,
        data: DecryptedKeyData,
    ) {
        if (keyBackupManager.hasBackup(walletId)) return
        val sessionPin = authManager.getSessionPin() ?: return
        // Fire-and-forget on an IO scope: callers invoke the read paths from
        // viewModelScope on Main, and KeyBackupManager.writeBackup runs a
        // 600k-iteration PBKDF2 plus a file write -- synchronous execution
        // would freeze the UI right after the biometric prompt on the user's
        // first send/reveal (Codex review on #311).
        backupScope.launch {
            try {
                val material = KeyMaterial(
                    privateKey = data.privateKeyHex,
                    mnemonic = data.mnemonic,
                    walletType = data.walletType,
                    mnemonicBackedUp = data.mnemonicBackedUp,
                )
                keyBackupManager.writeBackup(walletId, material, sessionPin)
                Log.i(TAG, "Opportunistic backup populated for $walletId after V2 read")
            } catch (e: Throwable) {
                Log.w(TAG, "Opportunistic backup write failed for $walletId", e)
            } finally {
                java.util.Arrays.fill(sessionPin, ' ')
            }
        }
    }

    /**
     * Process-lifetime scope for opportunistic backup writes. SupervisorJob
     * so one failed write can't cancel later ones; Dispatchers.IO for the
     * PBKDF2 + disk work.
     */
    private val backupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    companion object {
        private const val TAG = "WalletKeyReader"
    }
}
