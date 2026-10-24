package com.rjnr.pocketnode.data.vault

import android.content.Context
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.crypto.*
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retires only identity-proven sources. Shared Android key aliases are never deleted. */
class WalletLegacyCleanup(
    private val context: Context,
    private val backup: KeyBackupManager,
    private val db: AppDatabase,
    private val keyManager: KeyManager,
    private val legacy: KeyStoreMigrationHelper,
) : VaultLegacyCleanup {
    override suspend fun clean(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial): Boolean =
        cleanOwned(walletId, expectedGeneration, verifiedMaterial, null)

    override suspend fun cleanWithProof(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial, proof: BoundLegacyBackupProof): Boolean =
        cleanOwned(walletId, expectedGeneration, verifiedMaterial, proof)

    private suspend fun cleanOwned(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial, proof: BoundLegacyBackupProof?): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!walletId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return@withContext false
            val vaults = db.walletVaultDao()
            val keys = db.keyMaterialDao()
            val target = vaults.get(walletId) ?: return@withContext false
            val marker = keys.getByWalletId(walletId) ?: return@withContext false
            if (target.generation != expectedGeneration || marker.kdfVersion != 3 ||
                marker.encryptedPrivateKey.isNotEmpty() || marker.encryptedMnemonic != null || marker.iv.isNotEmpty()) return@withContext false
            val envelope = WalletEnvelopeCodec.decode(target.envelope)
            if (envelope.walletId != walletId || envelope.generation != expectedGeneration) return@withContext false
            val identity = addresses(verifiedMaterial.privateKey)
            val wallet = db.walletDao().getById(walletId)
            if (wallet != null && (wallet.mainnetAddress != identity.first || wallet.testnetAddress != identity.second)) return@withContext false

            if (proof != null && (!proof.proof.active || proof.proof.wallet != wallet ||
                !LegacyBackupProof.sameEnvelope(proof.committed, target))) return@withContext false
            val witnessed = proof?.proof?.copies.orEmpty()
            fun preserved(material: KeyMaterial): Boolean = addresses(material.privateKey) == identity &&
                (material.mnemonic == null || material.mnemonic == verifiedMaterial.mnemonic)
            // The scoped target's weak copies must also preserve their actual seed capability.
            if (witnessed[walletId]?.filterNotNull()?.any { !preserved(it.material) } == true) return@withContext false
            if (witnessed.any { (id, copies) -> backup.cleanupSnapshot(id) != copies.map { it?.fingerprint } }) return@withContext false
            val defaultWitness = witnessed["default"].orEmpty().filterNotNull()
            val witnessedDefault = defaultWitness.isNotEmpty() && defaultWitness.all { preserved(it.material) }
            val differentDefault = defaultWitness.isNotEmpty() && defaultWitness.all { addresses(it.material.privateKey) != identity }

            val scopedArchive = vaults.legacy(walletId)?.source()
            val scopedReadable = scopedArchive?.let { legacy.legacySourceForVaultCleanup(it) }
            if (scopedReadable != null && addresses(scopedReadable.privateKeyHex) == identity &&
                scopedReadable.mnemonic != null && scopedReadable.mnemonic != verifiedMaterial.mnemonic) return@withContext false
            val scopedBackup = backup.cleanupSnapshot(walletId)
            if (walletId != "default" && scopedBackup.any { it != null } && witnessed[walletId] == null) {
                val readableSource = scopedReadable
                    ?: return@withContext false
                if (addresses(readableSource.privateKeyHex) != identity ||
                    readableSource.mnemonic != null && readableSource.mnemonic != verifiedMaterial.mnemonic) return@withContext false
            }

            // Capture exact sources BEFORE reading them. Decryption/derivation is outside Room.
            val defaultRow = keys.getByWalletId("default")
            val archived = vaults.legacy("default")?.source()
            val source = if (walletId == "default") archived else defaultRow
            val globalFiles = preferenceFiles("default").map(::fingerprint)
            if (proof != null && proof.proof.globalPreferences != globalFiles) return@withContext false
            val defaultBackup = backup.cleanupSnapshot("default")
            val global = keyManager.defaultPreferencesForVaultCleanup()
            val decoded = source?.takeIf { it.kdfVersion == 1 }?.let { legacy.defaultSourceForVaultCleanup(it) }
            val readable = listOfNotNull(decoded, global)
            val matching = readable.filter { addresses(it.privateKeyHex) == identity }
            val defaultArtifacts = source != null || globalFiles.any { it != null } || defaultBackup.any { it != null }
            val ownsDefault = when {
                matching.isNotEmpty() || witnessedDefault -> true
                readable.isNotEmpty() || differentDefault -> {
                    // A different source may be skipped only when the target uses another namespace.
                    if (walletId == "default") return@withContext false
                    false
                }
                defaultArtifacts -> return@withContext false // Task 5 must obtain old-PIN ownership proof.
                else -> false
            }
            if (ownsDefault && (readable.any { addresses(it.privateKeyHex) != identity } ||
                readable.any { it.mnemonic != null && it.mnemonic != verifiedMaterial.mnemonic } ||
                (source != null && source.kdfVersion != 1 && !witnessedDefault))) return@withContext false
            if (ownsDefault && source?.kdfVersion == 1 && decoded == null) return@withContext false

            db.withTransaction {
                val current = vaults.get(walletId)
                if (vaults.legacy(walletId)?.source() != scopedArchive || backup.cleanupSnapshot(walletId) != scopedBackup) return@withTransaction false
                if (current == null || current.generation != target.generation || current.profileGeneration != target.profileGeneration ||
                    !current.envelope.contentEquals(target.envelope) || keys.getByWalletId(walletId) != marker ||
                    db.walletDao().getById(walletId) != wallet || keys.getByWalletId("default") != defaultRow ||
                    vaults.legacy("default")?.source() != archived) return@withTransaction false
                if (ownsDefault && (preferenceFiles("default").map(::fingerprint) != globalFiles ||
                    backup.cleanupSnapshot("default") != defaultBackup || keyManager.defaultPreferencesForVaultCleanup() != global)) return@withTransaction false
                if (proof != null && (!proof.proof.active || witnessed.any { (id, copies) ->
                    backup.cleanupSnapshot(id) != copies.map { it?.fingerprint }
                })) return@withTransaction false
                if (!backup.deleteCapturedCopies(walletId, scopedBackup) || !deletePreferences(walletId)) return@withTransaction false
                if (ownsDefault && walletId != "default") {
                    if (!backup.deleteCapturedCopies("default", defaultBackup) || !deletePreferences("default")) return@withTransaction false
                    // Files are checked first; a failed removal leaves the original row retryable.
                    if (defaultRow != null && defaultRow.kdfVersion == 1 && keys.deleteIfIv("default", defaultRow.iv) != 1) error("Legacy source changed")
                }
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun addresses(hex: String): Pair<String, String> {
        require(hex.length == 64)
        val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return try { WalletDerivation.walletInfo(key).let { it.mainnetAddress to it.testnetAddress } } finally { key.fill(0) }
    }
    private fun preferenceFiles(id: String): List<File> {
        val name = if (id == "default") "ckb_wallet_keys" else "ckb_wallet_keys_$id"
        val file = File(context.filesDir.parentFile, "shared_prefs/$name.xml")
        return listOf(file, File(file.path + ".bak"))
    }
    private fun fingerprint(file: File): String? = if (!file.exists()) null else file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun deletePreferences(id: String): Boolean {
        val name = if (id == "default") "ckb_wallet_keys" else "ckb_wallet_keys_$id"
        if (preferenceFiles(id).any { it.exists() }) context.deleteSharedPreferences(name)
        return preferenceFiles(id).none { it.exists() }
    }
}
