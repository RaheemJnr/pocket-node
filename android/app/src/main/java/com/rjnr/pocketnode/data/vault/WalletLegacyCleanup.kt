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
    override suspend fun clean(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial): Boolean = withContext(Dispatchers.IO) {
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

            // Capture exact sources BEFORE reading them. Decryption/derivation is outside Room.
            val defaultRow = keys.getByWalletId("default")
            val archived = vaults.legacy("default")?.source()
            val source = if (walletId == "default") archived else defaultRow
            val globalFiles = preferenceFiles("default").map(::fingerprint)
            val defaultBackup = backup.cleanupSnapshot("default")
            val global = keyManager.defaultPreferencesForVaultCleanup()
            val decoded = source?.takeIf { it.kdfVersion == 1 }?.let { legacy.defaultSourceForVaultCleanup(it) }
            val readable = listOfNotNull(decoded, global)
            val matching = readable.filter { addresses(it.privateKeyHex) == identity }
            val defaultArtifacts = source != null || globalFiles.any { it != null } || defaultBackup.any { it != null }
            val ownsDefault = when {
                walletId == "default" -> true
                matching.isNotEmpty() -> true
                readable.isNotEmpty() -> false // Proven unrelated namespace: preserve it without blocking this wallet.
                defaultArtifacts -> return@withContext false // Task 5 must obtain old-PIN ownership proof.
                else -> false
            }
            if (ownsDefault && (readable.any { addresses(it.privateKeyHex) != identity } ||
                readable.any { it.mnemonic != null && it.mnemonic != verifiedMaterial.mnemonic } ||
                (source != null && source.kdfVersion != 1 && walletId != "default"))) return@withContext false
            if (ownsDefault && source?.kdfVersion == 1 && decoded == null) return@withContext false

            db.withTransaction {
                val current = vaults.get(walletId)
                if (current == null || current.generation != target.generation || current.profileGeneration != target.profileGeneration ||
                    !current.envelope.contentEquals(target.envelope) || keys.getByWalletId(walletId) != marker ||
                    db.walletDao().getById(walletId) != wallet || keys.getByWalletId("default") != defaultRow ||
                    vaults.legacy("default")?.source() != archived) return@withTransaction false
                if (ownsDefault && (preferenceFiles("default").map(::fingerprint) != globalFiles ||
                    backup.cleanupSnapshot("default") != defaultBackup || keyManager.defaultPreferencesForVaultCleanup() != global)) return@withTransaction false
                if (!backup.deleteBackupChecked(walletId) || !deletePreferences(walletId)) return@withTransaction false
                if (ownsDefault && walletId != "default") {
                    if (!backup.deleteBackupChecked("default") || !deletePreferences("default")) return@withTransaction false
                    // Files are checked first; a failed removal leaves the original row retryable.
                    if (defaultRow != null && keys.deleteIfIv("default", defaultRow.iv) != 1) error("Legacy source changed")
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
