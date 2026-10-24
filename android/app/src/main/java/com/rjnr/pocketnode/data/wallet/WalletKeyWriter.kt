package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.vault.VaultAccessResult
import com.rjnr.pocketnode.data.vault.WalletVaultCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** Success means independent passphrase protection is committed and verified. */
@Singleton
class WalletKeyWriter @Inject constructor(private val coordinator: WalletVaultCoordinator) {
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

    suspend fun persistNewWallet(
        activity: FragmentActivity,
        walletId: String,
        bundle: WalletKeyBundle,
        walletType: String,
        mnemonicBackedUp: Boolean,
        promptTitle: String = "Secure wallet",
        promptSubtitle: String = "Protect this wallet with your wallet passphrase.",
    ): Result = when (val result = coordinator.persistNew(activity, walletId,
        KeyMaterial(bundle.privateKeyHex, bundle.mnemonic, if (bundle.mnemonic == null) "raw_key" else walletType, mnemonicBackedUp))) {
        is VaultAccessResult.Success -> Result.Success
        VaultAccessResult.Cancelled -> Result.Cancelled
        VaultAccessResult.CleanupRequired -> Result.WriteFailed(IllegalStateException("Legacy backup cleanup needs a retry."))
        VaultAccessResult.NoVault -> Result.WriteFailed(IllegalStateException("Wallet protection was not saved."))
        is VaultAccessResult.Unavailable -> Result.WriteFailed(IllegalStateException(result.reason))
    }

    /** Kept as a fail-closed compatibility boundary; creation requires a foreground Activity. */
    @Deprecated("Use persistNewWallet with an Activity and verified wallet passphrase")
    suspend fun persistNewWalletV1Fallback(walletId: String, bundle: WalletKeyBundle, walletType: String, mnemonicBackedUp: Boolean): Result =
        Result.WriteFailed(IllegalStateException("Open wallet setup to enter the wallet passphrase."))
}
