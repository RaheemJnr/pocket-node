package com.rjnr.pocketnode.data.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Activity-aware bridge for writing new wallet key material at kdfVersion=2.
 * Mirrors [WalletKeyReader] — both are the one boundary between the data
 * layer and UI layer that needs Activity scope.
 *
 * `KeyManager` stays Activity-free and produces plaintext bundles via
 * `encodePlaintextBundle`; this writer takes that bundle, drives the
 * BiometricPrompt CryptoObject dance, persists at kdfVersion=2 via
 * [KeystoreV2MigrationHelper.writeNewV2Row], and writes the PIN-encrypted
 * backup blob if a session PIN is available.
 *
 * Used by `OnboardingViewModel`, `MnemonicImportViewModel`, `AddWalletViewModel`,
 * `WalletSettingsViewModel` for all new-wallet and sub-account creation (#289).
 */
@Singleton
class WalletKeyWriter @Inject constructor(
    private val keyMaterialDao: KeyMaterialDao,
    private val keystoreV2MigrationHelper: KeystoreV2MigrationHelper,
    private val keyStoreMigrationHelper: KeyStoreMigrationHelper,
    private val encryptionManager: KeystoreEncryptionManager,
    private val authManager: AuthManager,
    private val keyBackupManager: KeyBackupManager,
) {

    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class AuthError(val errorCode: Int, val message: CharSequence) : Result()
        /**
         * The V2 Keystore key has been invalidated because the user changed
         * biometric enrollment. The caller must surface a re-enroll/redo flow
         * to the user; we cannot write a V2 row without a working V2 key.
         */
        object KeyInvalidated : Result()
        data class WriteFailed(val cause: Throwable) : Result()
        /**
         * V2 Keystore key cannot be created because the device has no
         * biometric enrollment AND no device PIN/pattern/password. The
         * onboarding screen should gate on this before reaching the writer
         * but the result is here as defense-in-depth. Caller surfaces a
         * dialog directing the user to Android Settings → Security.
         */
        object NoSecureLock : Result()
    }

    /**
     * Boxed non-Success [Result] for `WalletRepository.runCatching { }` flow. Repo
     * functions throw this so the typed [Result] propagates up to ViewModels via
     * `kotlin.Result.failure`. ViewModels inspect `(error as? PersistException)?.result`
     * to dispatch on Cancelled / AuthError / WriteFailed / KeyInvalidated.
     */
    class PersistException(val result: Result) :
        Exception("WalletKeyWriter returned non-Success: $result")

    /**
     * Persist a new wallet at kdfVersion=2.
     *
     * Steps:
     * 1. Obtain a V2 encrypt Cipher (lazily generates the V2 key alias on first call).
     *    May throw [KeyPermanentlyInvalidatedException] if the V2 key was wiped by
     *    biometric enrollment change — we translate to [Result.KeyInvalidated].
     * 2. BiometricPrompt via [AuthManager.authenticateForCipher]. User can cancel.
     * 3. On Success: encrypt the bundle, upsert KeyMaterialEntity at kdfVersion=2.
     * 4. Best-effort PIN backup write (no-op if no session PIN is available, e.g.
     *    on the first-wallet onboarding path before any PIN has been set).
     *
     * On step-3 failure: returns [Result.WriteFailed]; no Room row left behind.
     * On step-4 failure: best-effort rollback of the key_material row, then
     * returns [Result.WriteFailed].
     *
     * Cancellation safety: the post-auth section (Room write + PIN backup + rollback)
     * runs inside [NonCancellable] so a job cancellation between Room write and
     * backup write cannot leave an orphan key_material row without a backup blob.
     *
     * The caller is responsible for the post-persistence entity insert
     * (walletDao.insert, deactivateAll, prefs). On any non-Success Result,
     * the caller must NOT proceed with entity insertion.
     */
    suspend fun persistNewWallet(
        activity: FragmentActivity,
        walletId: String,
        bundle: WalletKeyBundle,
        walletType: String,
        mnemonicBackedUp: Boolean,
        // Copy must name the credential: users who reached this prompt after a
        // seed restore did not know WHICH password Android was asking for
        // (knmo, Nervos Talk, 2026-06). On devices without biometrics the
        // system sheet goes straight to the screen-lock PIN/pattern/password.
        promptTitle: String = "Secure wallet",
        promptSubtitle: String = "Use your phone's screen lock — fingerprint, face, or device PIN — to encrypt this wallet's keys.",
    ): Result {
        // #354 follow-up + security F1: a V2 auth-bound key cannot exist
        // without an enrolled biometric or device credential. Do NOT silently
        // downgrade to the V1 software key here — that hid a storage-security
        // downgrade from callers (Add Wallet, WalletSettings addSubAccount,
        // discovery restore) whose prompt copy implied secure persistence.
        // Return NoSecureLock; each UI flow decides whether to opt into
        // persistNewWalletV1Fallback AFTER showing the informed-consent
        // "Continue without a device lock?" dialog, matching onboarding.
        if (!authManager.isBiometricEnrolled() && !authManager.hasDeviceCredential()) {
            Log.i(TAG, "No secure lock on device — returning NoSecureLock for $walletId")
            return Result.NoSecureLock
        }

        val cipher = try {
            encryptionManager.newEncryptCipherV2()
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "V2 key invalidated when generating cipher for $walletId", e)
            return Result.KeyInvalidated
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            // KeyGenerator.init wraps the Keystore "Secure lock screen must be
            // enabled" IllegalStateException in InvalidAlgorithmParameterException
            // (seen on API 35 emulator) — the ISE catch below never fired for
            // it. Race path only now that the pre-gate above exists (e.g. lock
            // removed mid-flow): fall back to V1 like the gate would have.
            if (e.cause?.message?.contains("Secure lock screen", ignoreCase = true) == true) {
                // Race: the lock was removed mid-flow. Same as the pre-gate —
                // return NoSecureLock (F1) rather than silently downgrading.
                Log.w(TAG, "V2 key creation refused mid-flow (no secure lock); NoSecureLock", e)
                return Result.NoSecureLock
            }
            throw e
        } catch (e: IllegalStateException) {
            // Same condition, unwrapped form (older API levels).
            if (e.message?.contains("Secure lock screen", ignoreCase = true) == true) {
                Log.w(TAG, "V2 key creation refused mid-flow (no secure lock); NoSecureLock", e)
                return Result.NoSecureLock
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
                // Run the Room write + PIN backup + rollback under NonCancellable so a
                // job cancellation between the two writes cannot orphan a key_material
                // row without a matching backup blob.
                withContext(NonCancellable) {
                    val writeResult = keystoreV2MigrationHelper.writeNewV2Row(
                        walletId = walletId,
                        bundle = bundle,
                        v2EncryptCipher = authResult.cipher,
                        walletType = walletType,
                        mnemonicBackedUp = mnemonicBackedUp,
                    )
                    if (writeResult.isFailure) {
                        return@withContext Result.WriteFailed(
                            writeResult.exceptionOrNull()
                                ?: IllegalStateException("writeNewV2Row failed")
                        )
                    }

                    // Best-effort PIN backup. No-op if session PIN absent (first-wallet path).
                    val pin = authManager.getSessionPin()
                    if (pin != null) {
                        try {
                            try {
                                keyBackupManager.writeBackup(
                                    walletId,
                                    KeyMaterial(
                                        privateKey = bundle.privateKeyHex,
                                        mnemonic = bundle.mnemonic,
                                        walletType = walletType,
                                        mnemonicBackedUp = mnemonicBackedUp,
                                    ),
                                    pin = pin,
                                )
                            } catch (e: Throwable) {
                                Log.e(TAG, "PIN backup write failed for $walletId; rolling back key_material row", e)
                                // Best-effort rollback so we don't leave an orphan Room row
                                // without a matching backup blob.
                                try {
                                    keyMaterialDao.delete(walletId)
                                } catch (rollback: Throwable) {
                                    Log.e(TAG, "Rollback of key_material delete also failed for $walletId", rollback)
                                }
                                return@withContext Result.WriteFailed(e)
                            }
                        } finally {
                            // Zero the defensive copy returned by getSessionPin() so the
                            // PIN does not linger on the heap after this function returns.
                            Arrays.fill(pin, ' ')
                        }
                    }

                    Result.Success
                }
            }
        }
    }

    /**
     * Write a new wallet at kdfVersion=1 (software-only encryption, no
     * BiometricPrompt) for users whose device has no biometric AND no
     * device PIN/pattern/password set. The Android Keystore refuses to
     * mint the V2 key in that environment ("Secure lock screen must be
     * enabled..."), so [persistNewWallet] would otherwise hard-fail and
     * lock these users out of the app entirely.
     *
     * The wallet row is identical to the legacy v1.6.x format: V1 row at
     * `kdfVersion=1`, encrypted under the unrestricted Keystore key. The
     * existing AuthScreen migration loop in [AuthViewModel] will pick up
     * the row and upgrade it to V2 the moment the user enables a device
     * lock and cold-starts the app.
     *
     * Callers gate on [AuthManager.hasDeviceCredential] / biometric
     * enrollment and pick this method when neither is available; the
     * Onboarding/MnemonicImport screens surface a "Continue without a
     * device lock?" dialog with informed-consent copy.
     */
    suspend fun persistNewWalletV1Fallback(
        walletId: String,
        bundle: WalletKeyBundle,
        walletType: String,
        mnemonicBackedUp: Boolean,
    ): Result {
        return withContext(NonCancellable) {
            try {
                keyStoreMigrationHelper.migrateWallet(
                    walletId = walletId,
                    privateKeyHex = bundle.privateKeyHex,
                    mnemonic = bundle.mnemonic,
                    walletType = walletType,
                    mnemonicBackedUp = mnemonicBackedUp,
                )
                Log.i(TAG, "Wrote new V1 wallet row for $walletId (no device credential)")
                Result.Success
            } catch (e: Throwable) {
                Log.e(TAG, "V1 fallback write failed for $walletId", e)
                Result.WriteFailed(e)
            }
        }
    }

    companion object {
        private const val TAG = "WalletKeyWriter"
    }
}
