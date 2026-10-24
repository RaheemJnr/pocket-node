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
    private val reader: com.rjnr.pocketnode.data.wallet.WalletKeyReader,
    private val legacy: com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper,
) {
    suspend fun recover(activity: FragmentActivity, walletId: String): VaultAccessResult = recoverSources(activity, walletId, false)

    /** Fresh authentication and preservation of a retained scoped legacy archive, including when no backup remains. */
    suspend fun resumeCleanup(activity: FragmentActivity, walletId: String): VaultAccessResult = recoverSources(activity, walletId, true)

    private suspend fun recoverSources(activity: FragmentActivity, walletId: String, allowArchive: Boolean): VaultAccessResult = try {
        VaultOperationSession.run(activity) {
            val wallet = db.walletDao().getById(walletId) ?: return@run unavailable(allowArchive)
            val original = db.walletVaultDao().get(walletId)
            val source = db.keyMaterialDao().getByWalletId(walletId)
            val legacySources = setOf(walletId, "default").associateWith {
                db.keyMaterialDao().getByWalletId(it) to db.walletVaultDao().legacy(it)?.source()
            }
            val preferenceSources = setOf(walletId, "default").associateWith { keyManager.preferenceCleanupSnapshot(it) }
            val captured = withContext(Dispatchers.IO) {
                setOf(walletId, "default").associateWith { backup.captureCopies(it) }
            }
            try {
                val hasBackup = captured.values.flatten().any { it != null }
                val scoped = legacySources.getValue(walletId)
                val v2Sources = listOfNotNull(scoped.first, scoped.second).distinct().filter { it.kdfVersion == 2 }
                if (!hasBackup && (!allowArchive || original == null || scoped.second?.kdfVersion !in 1..2)) return@run unavailable(allowArchive)
                val copies: Map<String, List<LegacyBackupProof.Copy?>>? = if (hasBackup) {
                    val pin = prompt.requestLegacyPin(activity, "Old backup PIN for ${wallet.name}",
                        "Enter the app PIN used when this backup was created. It may differ from your current app PIN. Cancel keeps all recovery data.")
                        ?: return@run VaultAccessResult.Cancelled
                    try {
                        withContext(Dispatchers.IO) {
                            captured.mapValues { (_, values) -> values.map { bytes -> bytes?.let {
                                val material = backup.readCapturedBackup(it, pin) ?: return@withContext null
                                LegacyBackupProof.Copy(LegacyBackupProof.fingerprint(it), material)
                            } } }
                        }
                    } finally { pin.fill('\u0000') }
                } else captured.mapValues { (_, values) -> values.map { null } }
                if (copies == null) return@run unavailable(allowArchive)
                val backups = withContext(Dispatchers.IO) {
                    copies.values.flatten().filterNotNull().map { it.material }.filter { validator.validate(it, wallet) }
                }
                if (hasBackup && backups.isEmpty() && !allowArchive) return@run unavailable(allowArchive)
                val authenticated = mutableMapOf<com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity, com.rjnr.pocketnode.data.crypto.KeyMaterial>()
                val inaccessible = mutableSetOf<com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity>()
                // Authenticate exact bundles, including archived copies, before any raw activation.
                for (row in v2Sources) {
                    when (val extracted = reader.authenticateCapturedV2Source(activity, row)) {
                        is com.rjnr.pocketnode.data.wallet.WalletKeyReader.MaterialResult.Success -> try {
                            val value = com.rjnr.pocketnode.data.crypto.KeyMaterial(
                                extracted.privateKey.joinToString("") { "%02x".format(it) }, extracted.mnemonic,
                                if (extracted.mnemonic == null) "raw_key" else "mnemonic", extracted.mnemonicBackedUp)
                            if (!validator.validate(value, wallet) || backups.firstOrNull()?.let { it.privateKey != value.privateKey } == true) return@run unavailable(allowArchive)
                            authenticated[row] = value
                        } finally { extracted.privateKey.fill(0) }
                        com.rjnr.pocketnode.data.wallet.WalletKeyReader.MaterialResult.Cancelled -> return@run VaultAccessResult.Cancelled
                        com.rjnr.pocketnode.data.wallet.WalletKeyReader.MaterialResult.KeyInvalidated -> inaccessible += row
                        else -> return@run VaultAccessResult.Unavailable("Existing wallet authentication must finish before recovery. No source was replaced.")
                    }
                }
                val readableArchived = if (allowArchive && original != null) scoped.second?.takeIf { it.kdfVersion == 1 }?.let { row ->
                    legacy.legacySourceForVaultCleanup(row)?.let { data ->
                        com.rjnr.pocketnode.data.crypto.KeyMaterial(data.privateKeyHex, data.mnemonic,
                            if (data.mnemonic == null) "raw_key" else "mnemonic", data.mnemonicBackedUp)
                    }?.takeIf { validator.validate(it, wallet) }
                } else null
                val authenticatedMaterials = backups + authenticated.values + listOfNotNull(readableArchived)
                if (authenticatedMaterials.isEmpty()) return@run unavailable(allowArchive)
                // Read ESP only after real backup or V2 source authentication, bracketed by exact file witnesses.
                val globalFiles = preferenceSources.getValue("default")
                val preferences = preferenceSources.mapValues { (id, capturedFiles) ->
                    if (keyManager.preferenceCleanupSnapshot(id) != capturedFiles) return@run unavailable(allowArchive)
                    val data = keyManager.preferencesForVaultCleanup(id)
                    if (keyManager.preferenceCleanupSnapshot(id) != capturedFiles) return@run unavailable(allowArchive)
                    data
                }
                LegacyBackupProof(wallet, original, source, legacySources, globalFiles, copies).use { proof ->
                    // The authenticated source proves the private key. Preserve any richer readable
                    // V1 source for that exact key and path, including an archive from an earlier attempt.
                    proof.preferenceSources = preferenceSources
                    val preferenceSeeds = preferences.values.mapNotNull { data -> data?.takeIf { it.mnemonic != null }?.let {
                        com.rjnr.pocketnode.data.crypto.KeyMaterial(it.privateKeyHex, it.mnemonic, "mnemonic", it.mnemonicBackedUp)
                    }?.takeIf { it.privateKey == authenticatedMaterials.first().privateKey && validator.validate(it, wallet) } }
                    val matches = authenticatedMaterials + preferenceSeeds + withContext(Dispatchers.IO) {
                        legacySources.values.flatMap { listOfNotNull(it.first, it.second) }.mapNotNull { row ->
                            val data = legacy.legacySourceForVaultCleanup(row) ?: return@mapNotNull null
                            if (data.mnemonic == null) return@mapNotNull null
                            com.rjnr.pocketnode.data.crypto.KeyMaterial(data.privateKeyHex, data.mnemonic, "mnemonic", row.mnemonicBackedUp)
                                .takeIf { it.privateKey == authenticatedMaterials.first().privateKey && validator.validate(it, wallet) }
                        }
                    }
                    // Only permanent key loss may offer deliberate spending-only recovery, after
                    // every readable source and every actual backup copy has been authenticated.
                    if (inaccessible.isNotEmpty() && prompt.choose(activity, "Recover without the inaccessible source?",
                            "The existing device key is permanently missing or invalidated. This backup restores spending, but cannot restore a seed absent from all readable sources. Continue only if you accept losing that inaccessible seed and its other accounts. All readable recovery phrases are preserved.",
                            "Recover spending only", "Cancel") != true) return@run VaultAccessResult.Cancelled
                    proof.authenticatedSources = authenticated
                    proof.acknowledgedInaccessibleSources = inaccessible.map { LegacySourceAcknowledgment(wallet, it, original) }.toSet()
                    // Keep the actual seed whenever any authenticated copy contains it.
                    val material = matches.firstOrNull { it.mnemonic != null } ?: matches.first()
                    if (matches.any { it.mnemonic != null && it.mnemonic != material.mnemonic }) return@run unavailable(allowArchive)
                    coordinator.recoverWithLegacyProof(activity, walletId, material, proof)
                }
            } finally { captured.values.flatten().forEach { it?.fill(0) } }
        }
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { unavailable(allowArchive) }

    private fun unavailable(resume: Boolean) = VaultAccessResult.Unavailable(
        if (resume) "Legacy source authentication or cleanup did not complete. Sources remain preserved; retry authentication."
        else "No matching backup could be authenticated. Keep this wallet read-only and retry its old app PIN or recover with its recovery phrase/private key.")
}
