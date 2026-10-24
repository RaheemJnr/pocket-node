package com.rjnr.pocketnode.data.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.vault.*
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** The only public secret read boundary. Legacy material is released only after verified migration. */
@Singleton
class WalletKeyReader @Inject constructor(
    private val keyMaterialDao: KeyMaterialDao,
    private val legacy: KeyStoreMigrationHelper,
    private val encryption: KeystoreEncryptionManager,
    private val auth: AuthManager,
    private val recovery: RecoveryStateStore,
    private val coordinator: WalletVaultCoordinator,
    private val pin: PinManager,
    private val prompt: VaultPrompt,
    private val unlockPreference: WalletUnlockPreference,
) {
    sealed class Result {
        data class Success(val privateKey: ByteArray) : Result() {
            override fun toString() = "WalletKeyReader.Success([redacted])"
        }
        /** User cancelled the biometric prompt; no key returned. */
        object Cancelled : Result()
        /** Auth framework reported an error (not user-cancel). */
        data class AuthError(val errorCode: Int, val message: CharSequence) : Result()
        /** Wallet row missing, decrypt failed, or unknown kdfVersion. */
        data class NotAvailable(val reason: String, val kind: com.rjnr.pocketnode.data.vault.VaultFailureKind = com.rjnr.pocketnode.data.vault.VaultFailureKind.UNKNOWN) : Result()
        /**
         * The V2 Keystore key is missing or invalidated, for example after
         * removing the device screen lock or changing biometric enrollment.
         * Recovery needs an independent backup; generating another key does
         * not restore access to the existing encrypted bundle.
         */
        object KeyInvalidated : Result()
    }

    sealed class MaterialResult {
        data class Success(
            val privateKey: ByteArray,
            val mnemonic: String?,
            val walletType: String,
            val mnemonicBackedUp: Boolean,
            val backupWitness: WalletBackupWitness? = null,
        ) : MaterialResult() {
            override fun toString() = "WalletKeyReader.MaterialSuccess([redacted])"
        }
        object Cancelled : MaterialResult()
        data class AuthError(val errorCode: Int, val message: CharSequence) : MaterialResult()
        data class NotAvailable(val reason: String, val kind: com.rjnr.pocketnode.data.vault.VaultFailureKind = com.rjnr.pocketnode.data.vault.VaultFailureKind.UNKNOWN) : MaterialResult()
        object KeyInvalidated : MaterialResult()
    }

    suspend fun readPrivateKey(activity: FragmentActivity, walletId: String, promptTitle: String, promptSubtitle: String): Result =
        when (val result = readKeyMaterial(activity, walletId, promptTitle, promptSubtitle)) {
            is MaterialResult.Success -> Result.Success(result.privateKey)
            MaterialResult.Cancelled -> Result.Cancelled
            is MaterialResult.AuthError -> Result.AuthError(result.errorCode, result.message)
            is MaterialResult.NotAvailable -> Result.NotAvailable(result.reason, result.kind)
            MaterialResult.KeyInvalidated -> Result.KeyInvalidated
        }

    suspend fun readKeyMaterial(activity: FragmentActivity, walletId: String, promptTitle: String, promptSubtitle: String): MaterialResult {
        val source = keyMaterialDao.getByWalletId(walletId)
            ?: return unavailable(walletId, "Wallet keys are missing. Recover with an independent backup.")
        val access = when (source.kdfVersion) {
            3 -> coordinator.read(activity, walletId, promptTitle, promptSubtitle, unlockPreference.get())
            1, 2 -> {
                val extracted = extractLegacy(activity, walletId, source, promptTitle, promptSubtitle)
                if (extracted !is MaterialResult.Success) return extracted
                try {
                    if (keyMaterialDao.getByWalletId(walletId) != source) return MaterialResult.NotAvailable("Wallet changed. Try again.")
                    val hex = extracted.privateKey.joinToString("") { "%02x".format(it) }
                    coordinator.protectLegacy(activity, walletId, KeyMaterial(hex, extracted.mnemonic,
                        if (extracted.mnemonic == null) "raw_key" else extracted.walletType, source.mnemonicBackedUp), source.iv)
                } finally { extracted.privateKey.fill(0) }
            }
            else -> return MaterialResult.NotAvailable("Unsupported wallet key version.")
        }
        return when (access) {
            is VaultAccessResult.Success -> {
                recovery.removeUnverified(walletId)
                val material = access.material
                MaterialResult.Success(material.privateKey.hexToByteArray(), material.mnemonic, material.walletType,
                    keyMaterialDao.getMnemonicBackedUp(walletId) ?: material.mnemonicBackedUp, access.backupWitness)
            }
            VaultAccessResult.Cancelled -> MaterialResult.Cancelled
            VaultAccessResult.CleanupRequired -> MaterialResult.NotAvailable("Wallet protection is saved, but legacy backup cleanup needs a retry. Unlock again to finish.")
            VaultAccessResult.NoVault -> unavailable(walletId, "Wallet vault is missing. Recover with an independent backup.")
            is VaultAccessResult.Unavailable -> MaterialResult.NotAvailable(access.reason, access.kind)
        }
    }

    /** Recovery-only extraction of an exact current/archive V2 source; no migration or UI-free access. */
    internal suspend fun authenticateCapturedV2Source(activity: FragmentActivity,
        source: com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity): MaterialResult {
        require(source.kdfVersion == 2)
        return extractLegacy(activity, source.walletId, source, "Preserve existing recovery phrase",
            "Authenticate the existing wallet source before recovering its old backup.")
    }

    private suspend fun extractLegacy(activity: FragmentActivity, walletId: String,
        source: com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity, promptTitle: String, promptSubtitle: String): MaterialResult {
        var ownedKey: ByteArray? = null
        try {
            val result = VaultOperationSession.run(activity) { session ->
                val data = if (source.kdfVersion == 1) {
                    if (pin.hasPin()) {
                        val entered = prompt.requestLegacyPin(activity, "Authorize legacy wallet upgrade",
                            "Enter your existing app PIN to authorize upgrading this legacy wallet. A separate strong wallet passphrase is required next.")
                            ?: return@run MaterialResult.Cancelled
                        try {
                            if (!pin.verifyPinFromChars(entered)) return@run MaterialResult.NotAvailable("App PIN was not accepted. Try again later.")
                        } finally { entered.fill('\u0000') }
                    }
                    legacy.readDecryptedKey(walletId)
                } else {
                    val cipher = try { encryption.newDecryptCipherV2(source.iv) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val invalidated = generateSequence<Throwable>(e) { it.cause }.take(16).any {
                            it is KeyPermanentlyInvalidatedException || it is IllegalStateException && it.message?.contains("Secure lock screen", true) == true
                        }
                        if (invalidated) { recovery.addUnverified(walletId); recovery.markIncomplete(); return@run MaterialResult.KeyInvalidated }
                        return@run MaterialResult.NotAvailable("Device authentication is temporarily unavailable. Try again.")
                    }
                    val mode = when (unlockPreference.get()) {
                        VaultReadPreference.PASSPHRASE -> return@run MaterialResult.NotAvailable("This legacy wallet needs its existing Android authentication or an independent recovery backup before passphrase protection can be set up.")
                        VaultReadPreference.CREDENTIAL_ONLY -> VaultDevicePromptMode.CREDENTIAL_ONLY
                        VaultReadPreference.BIOMETRIC_ONLY -> VaultDevicePromptMode.BIOMETRIC_ONLY
                        VaultReadPreference.DEFAULT -> if (auth.isBiometricEnrolled()) VaultDevicePromptMode.BIOMETRIC_ONLY
                            else if (android.os.Build.VERSION.SDK_INT >= 30 && auth.hasDeviceCredential()) VaultDevicePromptMode.CREDENTIAL_ONLY
                            else VaultDevicePromptMode.BIOMETRIC_ONLY
                    }
                    val authMode = if (mode == VaultDevicePromptMode.CREDENTIAL_ONLY) AuthManager.CipherPromptMode.CREDENTIAL_ONLY else AuthManager.CipherPromptMode.BIOMETRIC_ONLY
                    val authenticated = session.systemAuthentication(mode) {
                        auth.authenticateForCipher(activity, cipher, promptTitle, promptSubtitle, promptMode = authMode)
                    }
                    when (authenticated) {
                        AuthManager.CipherAuthResult.Cancelled -> return@run MaterialResult.Cancelled
                        is AuthManager.CipherAuthResult.Error -> return@run MaterialResult.AuthError(authenticated.errorCode, authenticated.errString)
                        is AuthManager.CipherAuthResult.Success -> {
                            if (authenticated.cipher !== cipher) return@run MaterialResult.NotAvailable("Authentication did not authorize this wallet.")
                            legacy.readCapturedV2Source(source, authenticated.cipher)
                        }
                    }
                } ?: return@run unavailable(walletId, "Legacy keys are unreadable. Recover with an independent backup.")
                val key = data.privateKeyHex.hexToByteArray().also { ownedKey = it }
                MaterialResult.Success(key, data.mnemonic, data.walletType, source.mnemonicBackedUp)
            }
            ownedKey = null
            return result
        } finally { ownedKey?.fill(0) }
    }

    private fun unavailable(walletId: String, reason: String): MaterialResult {
        recovery.addUnverified(walletId)
        recovery.markIncomplete()
        return MaterialResult.NotAvailable(reason)
    }
}
