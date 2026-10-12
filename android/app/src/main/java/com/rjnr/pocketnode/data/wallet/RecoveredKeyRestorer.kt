package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.Script
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes key material decrypted from PIN backups (Recovery screen, "Recover
 * with PIN") into the normal key store, and decides when recovery is done.
 *
 * ## Completion invariant
 *
 * Recovery is complete only when the phone has at least one wallet row and
 * every wallet row (sub-accounts included) has a `key_material` row that is
 * not waiting for verification. Wallets that fail this are [missingWallets].
 * Until then the "recovery incomplete" marker in [RecoveryStateStore] stays
 * set, and the app opens on the Recovery screen at every cold start.
 *
 * ## Per wallet ([restore])
 * 1. The wallet must have a Room wallet row; a backup without one is
 *    skipped ([Outcome.NoWalletRow]) and its file kept.
 * 2. A wallet that already has a key row is never written: the row is only
 *    read back and checked.
 * 3. Otherwise the backup key must give the wallet's stored lock script,
 *    and a backup phrase must derive that same key at the wallet's
 *    derivation path, or nothing is written ([Outcome.AddressMismatch]).
 * 4. The bundle is written through [WalletKeyWriter] (the import writer,
 *    insert-or-fail): auth-bound V2 behind the screen lock when the phone
 *    has one, otherwise the V1 software key, and V1 only with the user's
 *    consent (`allowV1`, else [Outcome.NeedsNoLockConsent]). The V2 write
 *    also rewrites the wallet's PIN backup file under the session PIN, as
 *    import does.
 * 5. The wallet is recorded as unverified before the write, and only a
 *    successful read-back that gives the stored lock script clears it. A
 *    failed check deletes the row only if it is still the row written here.
 *
 * Never logs key material: wallet ids and outcomes only.
 */
@Singleton
class RecoveredKeyRestorer @Inject constructor(
    private val walletDao: WalletDao,
    private val keyMaterialDao: KeyMaterialDao,
    private val keyWriter: WalletKeyWriter,
    private val keyReader: WalletKeyReader,
    private val authManager: AuthManager,
    private val keyManager: KeyManager,
    private val mnemonicManager: MnemonicManager,
    private val recoveryState: RecoveryStateStore,
    private val logger: Logger,
) {

    sealed class Outcome {
        /** Stored (or already stored), read back and matched the wallet. */
        object Restored : Outcome()
        /** No Room wallet row for this backup; nothing written. */
        object NoWalletRow : Outcome()
        /** The wallet has no key row and no backup was decrypted for it. */
        object NoBackup : Outcome()
        /**
         * The backup, or a key already stored for the wallet, does not match
         * the wallet. Nothing was overwritten.
         */
        object AddressMismatch : Outcome()
        /**
         * The phone has no screen lock and the user has not agreed to the
         * software-only key yet; nothing was written.
         */
        object NeedsNoLockConsent : Outcome()
        /** The user dismissed an unlock prompt; a retry can finish it. */
        object Cancelled : Outcome()
        /** Write or read-back failed; [reason] never holds key material. */
        data class Failed(val reason: String) : Outcome()
    }

    /** Wallet rows without verified keys; see the completion invariant. */
    suspend fun missingWallets(): List<WalletEntity> {
        val unverified = recoveryState.unverifiedWalletIds()
        return walletDao.getAll().filter {
            it.walletId in unverified || keyMaterialDao.getKdfVersion(it.walletId) == null
        }
    }

    suspend fun walletIds(): Set<String> = walletDao.getAll().map { it.walletId }.toSet()

    /** The Recovery screen is showing: keep it until recovery is complete. */
    fun markIncomplete() = recoveryState.markIncomplete()

    fun isMarkedIncomplete(): Boolean = recoveryState.isIncomplete()

    /**
     * When the completion invariant holds, clears the marker and the startup
     * corruption flag and returns true. Otherwise changes nothing.
     */
    suspend fun completeIfDone(): Boolean {
        if (walletDao.getAll().isEmpty() || missingWallets().isNotEmpty()) return false
        recoveryState.clear()
        @Suppress("DEPRECATION")
        keyManager.resetCorruptionFlag()
        logger.i(TAG, "Recovery complete: every wallet has verified keys")
        return true
    }

    /**
     * True when a restore would store keys at V1 (no biometric, no screen
     * lock), which needs the no-lock consent first.
     */
    fun needsNoLockConsent(): Boolean =
        !authManager.isBiometricEnrolled() && !authManager.hasDeviceCredential()

    suspend fun restore(
        activity: FragmentActivity,
        walletId: String,
        material: KeyMaterial?,
        allowV1: Boolean,
    ): Outcome {
        val wallet = walletDao.getById(walletId)
        if (wallet == null) {
            logger.w(TAG, "No wallet row for backup $walletId; skipped, backup kept")
            return Outcome.NoWalletRow
        }
        if (keyMaterialDao.getKdfVersion(walletId) != null) {
            // Written by an earlier attempt, or never lost: verify only.
            return verify(activity, walletId, wallet, writtenIv = null)
        }
        if (material == null) return Outcome.NoBackup

        val keyBytes = runCatching { material.privateKey.hexToByteArray() }.getOrNull()
            ?.takeIf { it.size == PRIVATE_KEY_SIZE }
            ?: return Outcome.Failed("backup key is not a 32-byte key").also {
                logger.w(TAG, "Backup for $walletId does not hold a 32-byte key")
            }
        try {
            if (!matchesWallet(keyBytes, wallet) || !phraseMatchesKey(material.mnemonic, keyBytes, wallet)) {
                logger.w(TAG, "Backup for $walletId does not match the wallet; not written")
                return Outcome.AddressMismatch
            }
            if (needsNoLockConsent() && !allowV1) {
                logger.i(TAG, "No screen lock and no consent yet for $walletId; not written")
                return Outcome.NeedsNoLockConsent
            }

            // Recorded before the write: a row whose read-back never
            // completes stays missing across launches.
            recoveryState.addUnverified(walletId)
            when (val written = write(activity, walletId, keyBytes, material)) {
                WalletKeyWriter.Result.Success -> Unit
                WalletKeyWriter.Result.Cancelled -> return Outcome.Cancelled
                is WalletKeyWriter.Result.AuthError ->
                    return Outcome.Failed("unlock error ${written.errorCode}")
                WalletKeyWriter.Result.KeyInvalidated -> return Outcome.Failed("device key invalidated")
                WalletKeyWriter.Result.NoSecureLock -> return Outcome.NeedsNoLockConsent
                is WalletKeyWriter.Result.WriteFailed -> {
                    logger.e(TAG, "Writing restored keys failed for $walletId", written.cause)
                    return Outcome.Failed("write failed")
                }
            }
            // Every write draws a fresh IV: it identifies the row written here.
            val writtenIv = keyMaterialDao.getByWalletId(walletId)?.iv
            return verify(activity, walletId, wallet, writtenIv)
        } finally {
            keyBytes.fill(0)
        }
    }

    private suspend fun write(
        activity: FragmentActivity,
        walletId: String,
        keyBytes: ByteArray,
        material: KeyMaterial,
    ): WalletKeyWriter.Result {
        // Same bundle format a fresh import produces.
        val bundle = WalletDerivation.encodePlaintextBundle(keyBytes, material.mnemonic?.let(::words))
        return if (!needsNoLockConsent()) {
            keyWriter.persistNewWallet(
                activity = activity,
                walletId = walletId,
                bundle = bundle,
                walletType = material.walletType,
                mnemonicBackedUp = material.mnemonicBackedUp,
                promptTitle = "Restore wallet keys",
                promptSubtitle = "Use your phone's screen lock to save this wallet's restored keys.",
            )
        } else {
            keyWriter.persistNewWalletV1Fallback(
                walletId = walletId,
                bundle = bundle,
                walletType = material.walletType,
                mnemonicBackedUp = material.mnemonicBackedUp,
            )
        }
    }

    private suspend fun verify(
        activity: FragmentActivity,
        walletId: String,
        wallet: WalletEntity,
        writtenIv: ByteArray?,
    ): Outcome {
        val read = keyReader.readPrivateKey(
            activity = activity,
            walletId = walletId,
            promptTitle = "Check restored keys",
            promptSubtitle = "Use your phone's screen lock to confirm this wallet's keys were saved.",
        )
        val outcome = when (read) {
            is WalletKeyReader.Result.Success -> try {
                if (matchesWallet(read.privateKey, wallet)) Outcome.Restored else Outcome.AddressMismatch
            } finally {
                read.privateKey.fill(0)
            }
            // The row stays unverified; the next attempt reads it again.
            WalletKeyReader.Result.Cancelled -> return Outcome.Cancelled
            is WalletKeyReader.Result.AuthError -> Outcome.Failed("unlock error ${read.errorCode}")
            is WalletKeyReader.Result.NotAvailable -> Outcome.Failed("stored keys not readable")
            WalletKeyReader.Result.KeyInvalidated -> Outcome.Failed("device key invalidated")
        }
        if (outcome == Outcome.Restored) {
            recoveryState.removeUnverified(walletId)
            logger.i(TAG, "Verified restored keys for $walletId")
            return outcome
        }
        logger.w(TAG, "Read-back check failed for $walletId")
        if (writtenIv != null) {
            // Only the row written in this call, and only while it is still that row.
            runCatching { keyMaterialDao.deleteIfIv(walletId, writtenIv) }
                .onFailure { logger.e(TAG, "Removing the unverified row failed for $walletId", it) }
        }
        return outcome
    }

    /**
     * The key gives the lock script stored for the wallet: both addresses
     * when both are stored, the one that is when the other is blank.
     */
    private fun matchesWallet(privateKey: ByteArray, wallet: WalletEntity): Boolean {
        val script = runCatching { WalletDerivation.lockScript(WalletDerivation.publicKey(privateKey)) }
            .getOrNull() ?: return false
        val stored = listOf(wallet.mainnetAddress, wallet.testnetAddress).filter { it.isNotBlank() }
        if (stored.isEmpty()) return false
        return stored.all { address ->
            runCatching { sameScript(WalletDerivation.lockScriptFromAddress(address), script) }.getOrDefault(false)
        }
    }

    private fun sameScript(a: Script, b: Script): Boolean =
        a.codeHash.equals(b.codeHash, ignoreCase = true) &&
            a.hashType == b.hashType &&
            a.args.equals(b.args, ignoreCase = true)

    /**
     * A backup that carries a phrase must derive its own private key at the
     * wallet's derivation path; a backup without one has nothing to check.
     */
    private fun phraseMatchesKey(mnemonic: String?, keyBytes: ByteArray, wallet: WalletEntity): Boolean {
        if (mnemonic == null) return true
        val (account, chain, index) = derivationIndices(wallet)
        val seed = runCatching { mnemonicManager.mnemonicToSeed(words(mnemonic)) }.getOrNull() ?: return false
        val derived = try {
            runCatching { mnemonicManager.derivePrivateKey(seed, account, chain, index) }.getOrNull()
        } finally {
            seed.fill(0)
        }
        return try {
            derived != null && derived.contentEquals(keyBytes)
        } finally {
            derived?.fill(0)
        }
    }

    private fun derivationIndices(wallet: WalletEntity): Triple<Int, Int, Int> {
        val match = wallet.derivationPath?.let { PATH.matchEntire(it.trim()) }
            ?: return Triple(wallet.accountIndex, 0, 0)
        return Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }

    private fun words(mnemonic: String): List<String> = mnemonic.trim().split(WHITESPACE)

    private companion object {
        const val TAG = "RecoveredKeyRestorer"
        const val PRIVATE_KEY_SIZE = 32
        val PATH = Regex("""m/44'/309'/(\d+)'/(\d+)/(\d+)""")
        val WHITESPACE = Regex("""\s+""")
    }
}
