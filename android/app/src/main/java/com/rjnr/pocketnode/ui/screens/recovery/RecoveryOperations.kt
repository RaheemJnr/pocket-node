package com.rjnr.pocketnode.ui.screens.recovery

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.vault.VaultFailureKind
import com.rjnr.pocketnode.data.wallet.RecoveryStateStore
import com.rjnr.pocketnode.data.vault.VaultAccessResult
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import com.rjnr.pocketnode.data.vault.WalletVaultStore
import kotlinx.coroutines.CancellationException
import com.rjnr.pocketnode.data.vault.WalletVaultCoordinator
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.ui.security.RecoverySecretDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Operation-owned secrets never cross the ViewModel boundary. No credentials or material are fields. */
class RecoveryOperations @Inject constructor(
    private val restorer: RecoveredKeyRestorer,
    private val db: AppDatabase,
    private val backups: KeyBackupManager,
    private val input: RecoverySecretDialog,
    private val vault: WalletVaultCoordinator,
    private val recoveryState: RecoveryStateStore,
    private val store: WalletVaultStore,
) {
    suspend fun wallets(): List<MissingWallet> {
        val pending = db.walletVaultDao().pendingCleanup().toSet()
        val oldProfiles = try { store.snapshot().unrecoveredWalletIds }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { db.walletVaultDao().all().map { it.walletId }.toSet() }
        val missing = restorer.missingWallets().map { it.walletId }.toSet() + pending + oldProfiles
        return db.walletDao().getAll().filter { it.walletId in missing }.map { wallet ->
            val hasBackup = withContext(Dispatchers.IO) {
                // Include interrupted copies and the historical default source. Hashes are nonsecret.
                setOf(wallet.walletId, "default").any { id ->
                    runCatching { backups.cleanupSnapshot(id).any { it != null } }.getOrDefault(true)
                }
            }
            MissingWallet(wallet.walletId, wallet.name, wallet.type == "raw_key", hasBackup,
                wallet.walletId in pending, db.walletVaultDao().legacy(wallet.walletId)?.kdfVersion in 1..2)
        }
    }
    fun markIncomplete() = restorer.markIncomplete()
    suspend fun complete(): Boolean = wallets().isEmpty() && restorer.walletIds().isNotEmpty() && restorer.completeIfDone()
    suspend fun backup(activity: FragmentActivity, id: String) = restorer.restoreLegacyBackup(activity, id)
    suspend fun cleanup(activity: FragmentActivity, id: String) = restorer.resumeLegacyCleanup(activity, id)

    suspend fun unlock(activity: FragmentActivity, id: String, preference: VaultReadPreference): RecoveredKeyRestorer.Outcome {
        // Reader's ordinary authenticated route owns health-marker removal.
        return when (val result = vault.read(activity, id, "Recover wallet access", "Authenticate this wallet", preference)) {
            is VaultAccessResult.Success -> {
                recoveryState.removeUnverified(id)
                RecoveredKeyRestorer.Outcome.Restored
            }
            VaultAccessResult.Cancelled -> RecoveredKeyRestorer.Outcome.Cancelled
            VaultAccessResult.CleanupRequired -> RecoveredKeyRestorer.Outcome.CleanupRequired
            is VaultAccessResult.Unavailable -> RecoveredKeyRestorer.Outcome.Failed(when (result.kind) {
                VaultFailureKind.MISSING_DEVICE_KEY, VaultFailureKind.INVALIDATED_DEVICE_KEY, VaultFailureKind.NO_DEVICE_WRAP,
                VaultFailureKind.NO_DEVICE_LOCK, VaultFailureKind.UNSUPPORTED_DEVICE_AUTH ->
                    "Device access is unavailable. Choose the independent wallet passphrase; device-key loss does not prove the seed is lost."
                VaultFailureKind.CREDENTIAL_OR_WRAP_INTEGRITY ->
                    "The credential did not authenticate, or its encrypted wrap is damaged. Nothing was replaced. Check the wallet passphrase."
                VaultFailureKind.PAYLOAD_INTEGRITY, VaultFailureKind.MALFORMED_OR_UNSUPPORTED, VaultFailureKind.VERIFICATION_FAILED ->
                    "Saved data could not be verified. All sources are preserved; this is not proof of permanent key loss."
                else -> "Authentication is temporarily unavailable. Retry; no replacement was authorized."
            })
            else -> RecoveredKeyRestorer.Outcome.Failed("Authentication did not complete. Retry the selected method or use an external recovery secret; all saved sources are preserved.")
        }
    }
    suspend fun external(activity: FragmentActivity, id: String, privateKey: Boolean): RecoveredKeyRestorer.RekeyResult = com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) owned@ {
        // Check persisted cleanup state again; stale UI never authorizes replacement.
        if (id in db.walletVaultDao().pendingCleanup()) return@owned RecoveredKeyRestorer.RekeyResult.Done(mapOf(id to RecoveredKeyRestorer.Outcome.CleanupRequired))
        val secret = input.request(activity, privateKey)
            ?: return@owned RecoveredKeyRestorer.RekeyResult.Done(mapOf(id to RecoveredKeyRestorer.Outcome.Cancelled))
        try {
            recoveryState.addUnverified(id)
            recoveryState.markIncomplete()
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                return@owned RecoveredKeyRestorer.RekeyResult.Done(mapOf(id to RecoveredKeyRestorer.Outcome.Cancelled))
            if (privateKey) {
                val bytes = runCatching { secret.concatToString().trim().hexToByteArray() }.getOrNull()
                    ?: return@owned RecoveredKeyRestorer.RekeyResult.InvalidInput
                secret.fill('\u0000')
                return@owned try { restorer.rekeyFromPrivateKey(activity, id, bytes, false) } finally { bytes.fill(0) }
            }
            // BIP39 conversion requires immutable JVM Strings; they remain operation-local, never UI state.
            val words = secret.concatToString().trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            secret.fill('\u0000')
            return@owned restorer.rekeyFromPhrase(activity, id, words, false)
        } finally { secret.fill('\u0000') }
    }
}
