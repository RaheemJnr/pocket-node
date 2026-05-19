package com.rjnr.pocketnode.data.migration

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-side orchestrator that drives the v1.6.x → v1.7.0 wallet key
 * migration end-to-end: discovers pending wallets via
 * [KeystoreV2MigrationHelper.pendingWalletIds], walks each wallet through
 * a BiometricPrompt CryptoObject flow, then calls [KeystoreV2MigrationHelper.finalize]
 * once all wallets are on V2.
 *
 * ## Status
 *
 * Wired but **not yet invoked from app code**. Sub-PR 5 of #213 will
 * connect this from MainActivity post-PIN-unlock once every key-reading
 * call site (Send, mnemonic backup, sub-account derivation, DAO ops) is
 * V2-aware. Calling [runMigration] today would re-encrypt key material
 * under the V2 key and then break every call site that still uses V1.
 *
 * ## Why a separate class
 *
 * The V2 helper itself is UI-agnostic — it accepts a pre-authenticated
 * Cipher and writes one row. The runner owns the activity-scoped concerns:
 * which prompt copy to show, what to do on cancel, how to resume after
 * a process kill. Keeping these out of the helper means the helper stays
 * unit-testable with Robolectric in-memory keys, and the runner can be
 * exercised by instrumentation tests later without dragging
 * `FragmentActivity` into the data layer.
 */
@Singleton
class KeystoreV2MigrationRunner @Inject constructor(
    private val helper: KeystoreV2MigrationHelper,
    private val encryptionManager: KeystoreEncryptionManager,
    private val authManager: AuthManager,
) {

    sealed class Outcome {
        /** Every wallet migrated and `finalize()` succeeded. */
        object Completed : Outcome()
        /** User cancelled at least one prompt. Remaining wallets are still on V1. */
        data class Cancelled(val pendingCount: Int) : Outcome()
        /** Auth failed for at least one wallet (not user-cancel). Migration is partial. */
        data class Failed(val pendingCount: Int, val reason: String) : Outcome()
        /** Nothing to do — either no wallets exist or migration has already finalized. */
        object NothingToDo : Outcome()
    }

    /**
     * Drive the V2 migration to completion. Re-prompts per wallet until
     * either every wallet is on V2 (then calls [KeystoreV2MigrationHelper.finalize])
     * or the user cancels.
     *
     * Idempotent: re-running after a partial migration picks up where it
     * left off via [KeystoreV2MigrationHelper.pendingWalletIds].
     *
     * @param activity host for `BiometricPrompt`. Must be alive for the
     *   duration of the call.
     * @param promptTitle title shown on every per-wallet prompt.
     * @param promptSubtitle subtitle shown on every per-wallet prompt.
     */
    suspend fun runMigration(
        activity: FragmentActivity,
        promptTitle: String = "Upgrade wallet security",
        promptSubtitle: String = "Unlock to re-encrypt your wallet keys.",
    ): Outcome {
        if (helper.isMigrationComplete()) return Outcome.NothingToDo

        val pending = helper.pendingWalletIds()
        if (pending.isEmpty()) {
            return helper.finalize().fold(
                onSuccess = { Outcome.Completed },
                onFailure = { Outcome.Failed(0, it.message ?: "finalize failed") }
            )
        }

        for (walletId in pending) {
            val cipher = encryptionManager.newEncryptCipherV2()
            val authResult = authManager.authenticateForCipher(
                activity = activity,
                cipher = cipher,
                title = promptTitle,
                subtitle = promptSubtitle,
            )
            when (authResult) {
                is AuthManager.CipherAuthResult.Cancelled -> {
                    Log.i(TAG, "User cancelled V2 migration prompt for $walletId")
                    return Outcome.Cancelled(helper.pendingWalletIds().size)
                }
                is AuthManager.CipherAuthResult.Error -> {
                    Log.e(TAG, "Auth error code=${authResult.errorCode} for $walletId: ${authResult.errString}")
                    return Outcome.Failed(
                        helper.pendingWalletIds().size,
                        "auth error ${authResult.errorCode}: ${authResult.errString}"
                    )
                }
                is AuthManager.CipherAuthResult.Success -> {
                    val migrate = helper.migrateWallet(walletId, authResult.cipher)
                    if (migrate.isFailure) {
                        val msg = migrate.exceptionOrNull()?.message ?: "migrate failed"
                        Log.e(TAG, "Migrate failed for $walletId: $msg")
                        return Outcome.Failed(helper.pendingWalletIds().size, msg)
                    }
                }
            }
        }

        return helper.finalize().fold(
            onSuccess = { Outcome.Completed },
            onFailure = { Outcome.Failed(helper.pendingWalletIds().size, it.message ?: "finalize failed") }
        )
    }

    companion object {
        private const val TAG = "KeystoreV2Migration"
    }
}
