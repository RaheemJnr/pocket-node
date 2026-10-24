package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.database.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Owns foreground old-PIN input, exact ciphertext capture and proof lifetime; never caches secrets. */
class LegacyBackupRecovery @Inject constructor(
    private val db: AppDatabase,
    private val backup: KeyBackupManager,
    private val coordinator: WalletVaultCoordinator,
    private val prompt: VaultPrompt,
    private val validator: WalletSecretValidator,
    private val keyManager: com.rjnr.pocketnode.data.wallet.KeyManager,
    private val legacy: com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper,
) {
    suspend fun recover(activity: FragmentActivity, walletId: String): VaultAccessResult = try {
        VaultOperationSession.run(activity) {
            val wallet = db.walletDao().getById(walletId) ?: return@run unavailable()
            val original = db.walletVaultDao().get(walletId)
            val source = db.keyMaterialDao().getByWalletId(walletId)
            val legacySources = setOf(walletId, "default").associateWith {
                db.keyMaterialDao().getByWalletId(it) to db.walletVaultDao().legacy(it)?.source()
            }
            val captured = withContext(Dispatchers.IO) {
                setOf(walletId, "default").associateWith { backup.captureCopies(it) }
            }
            try {
                if (captured.values.flatten().all { it == null }) return@run unavailable()
                val pin = prompt.requestLegacyPin(activity, "Old backup PIN for ${wallet.name}",
                    "Enter the app PIN used when this backup was created. It may differ from your current app PIN. Cancel keeps all recovery data.")
                    ?: return@run VaultAccessResult.Cancelled
                val copies: Map<String, List<LegacyBackupProof.Copy?>>? = try {
                    withContext(Dispatchers.IO) {
                        captured.mapValues { (_, values) -> values.map { bytes -> bytes?.let {
                            val material = backup.readCapturedBackup(it, pin) ?: return@withContext null
                            LegacyBackupProof.Copy(LegacyBackupProof.fingerprint(it), material)
                        } } }
                    }
                } finally { pin.fill('\u0000') }
                if (copies == null) return@run unavailable()
                // Read ESP only after real backup authentication, bracketed by exact file witnesses.
                val globalFiles = keyManager.defaultPreferenceCleanupSnapshot()
                val global = keyManager.defaultPreferencesForVaultCleanup()
                if (keyManager.defaultPreferenceCleanupSnapshot() != globalFiles) return@run unavailable()
                LegacyBackupProof(wallet, original, source, legacySources, globalFiles, copies).use { proof ->
                    val backups = withContext(Dispatchers.IO) {
                        copies.values.flatten().filterNotNull().map { it.material }.filter { validator.validate(it, wallet) }
                    }
                    if (backups.isEmpty()) return@run unavailable()
                    // The authenticated backup proves the private key. Preserve any richer readable
                    // V1 source for that exact key and path, including an archive from an earlier attempt.
                    val globalSeed = global?.takeIf { it.mnemonic != null }?.let {
                        com.rjnr.pocketnode.data.crypto.KeyMaterial(it.privateKeyHex, it.mnemonic, "mnemonic", it.mnemonicBackedUp)
                    }?.takeIf { it.privateKey == backups.first().privateKey && validator.validate(it, wallet) }
                    val matches = backups + listOfNotNull(globalSeed) + withContext(Dispatchers.IO) {
                        legacySources.values.flatMap { listOfNotNull(it.first, it.second) }.mapNotNull { row ->
                            val data = legacy.legacySourceForVaultCleanup(row) ?: return@mapNotNull null
                            if (data.mnemonic == null) return@mapNotNull null
                            com.rjnr.pocketnode.data.crypto.KeyMaterial(data.privateKeyHex, data.mnemonic, "mnemonic", row.mnemonicBackedUp)
                                .takeIf { it.privateKey == backups.first().privateKey && validator.validate(it, wallet) }
                        }
                    }
                    // Keep the actual seed whenever any authenticated copy contains it.
                    val material = matches.firstOrNull { it.mnemonic != null } ?: matches.first()
                    if (matches.any { it.mnemonic != null && it.mnemonic != material.mnemonic }) return@run unavailable()
                    coordinator.recoverWithLegacyProof(activity, walletId, material, proof)
                }
            } finally { captured.values.flatten().forEach { it?.fill(0) } }
        }
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { unavailable() }

    private fun unavailable() = VaultAccessResult.Unavailable(
        "No matching backup could be authenticated. Keep this wallet read-only and retry its old app PIN or recover with its recovery phrase/private key.")
}
