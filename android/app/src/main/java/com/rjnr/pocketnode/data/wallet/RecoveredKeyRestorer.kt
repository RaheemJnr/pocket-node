package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
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
 * not waiting for verification, or when there is nothing to recover at all
 * (no wallet row, no key row, no backup file). Wallets that fail this are [missingWallets].
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
 * ## Re-key from a phrase or private key
 * A missing wallet with no usable backup can be re-keyed from its phrase
 * ([rekeyFromPhrase], derived at each row's own path, so a parent's phrase
 * also re-keys its missing sub-accounts) or, for a raw-key wallet, its
 * private key ([rekeyFromPrivateKey]). Both go through [restore] for the
 * existing rows only: no wallet row is created and no row that is not
 * missing is touched.
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
    private val keyBackupManager: KeyBackupManager,
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

    /**
     * Wallet rows without verified keys; see the completion invariant. A row
     * with no `key_material` row whose keys are still in the legacy
     * per-wallet store (an upgrade from before that table, migrated in the
     * background) is not missing: its keys are there.
     */
    suspend fun missingWallets(): List<WalletEntity> {
        val unverified = recoveryState.unverifiedWalletIds()
        return walletDao.getAll().filter {
            it.walletId in unverified ||
                (keyMaterialDao.getKdfVersion(it.walletId) == null && !keyManager.hasLegacyKeyFile(it.walletId))
        }
    }

    suspend fun walletIds(): Set<String> = walletDao.getAll().map { it.walletId }.toSet()

    /** The Recovery screen is showing: keep it until recovery is complete. */
    fun markIncomplete() = recoveryState.markIncomplete()

    fun isMarkedIncomplete(): Boolean = recoveryState.isIncomplete()

    /**
     * When the completion invariant holds, clears the marker and the startup
     * corruption flag and returns true. Otherwise changes nothing.
     *
     * A phone with nothing to recover (no wallet row, no key row and no
     * backup file) also counts as complete, so a stale marker never keeps a
     * fresh install on the Recovery screen. Then only the marker is cleared:
     * the corruption flag stays up so onboarding can still say why the
     * wallet is gone.
     */
    suspend fun completeIfDone(): Boolean {
        val wallets = walletDao.getAll()
        if (wallets.isEmpty()) {
            val nothingToRecover = keyMaterialDao.count() == 0 && !keyBackupManager.hasAnyBackups()
            if (!nothingToRecover) return false
            recoveryState.clear()
            logger.i(TAG, "Nothing to recover: marker cleared")
            return true
        }
        if (missingWallets().isNotEmpty()) return false
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
        val existingIv = keyMaterialDao.getByWalletId(walletId)?.iv
        if (existingIv != null) {
            // Written by an earlier attempt, or never lost: verify it.
            val checked = verifyChecked(activity, walletId, wallet, writtenIv = null)
            val outcome = checked.outcome
            // A row that was never verified and is proven unusable (it does
            // not decrypt, its device key is gone, or it holds another key)
            // would block every retry. It is dropped and written again, but
            // only when the new material matches this wallet and could be
            // written now (consent included). A transient unlock error
            // (lockout, timeout, hardware busy) is a retry, never a reason to
            // drop the row; a verified row is never dropped.
            //
            // Between the delete and the write the wallet has no key row.
            // That gap is accepted: the material is in hand (a backup file
            // that is kept, or a phrase or key the user just entered), and a
            // failed write leaves the wallet missing, so the next attempt
            // writes it again.
            if (!checked.replaceable || walletId !in recoveryState.unverifiedWalletIds() || material == null) {
                return outcome
            }
            if (!materialMatches(material, wallet)) return outcome
            if (needsNoLockConsent() && !allowV1) return Outcome.NeedsNoLockConsent
            logger.w(TAG, "Unverified key row for $walletId is unusable; writing it again")
            runCatching { keyMaterialDao.deleteIfIv(walletId, existingIv) }
                .onFailure { logger.e(TAG, "Removing the unusable row failed for $walletId", it) }
            if (keyMaterialDao.getKdfVersion(walletId) != null) return outcome
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

    /** Result of re-keying a missing wallet from its phrase or private key. */
    sealed class RekeyResult {
        /** Not a valid phrase or a 32-byte key. Nothing written. */
        object InvalidInput : RekeyResult()
        /** The wallet is not missing keys (any more). Nothing written. */
        object NotMissing : RekeyResult()
        /** The phrase or key does not give this wallet's lock script. Nothing written. */
        object NotThisWallet : RekeyResult()
        /** Each wallet row the input matched, with its restore outcome. */
        data class Done(val outcomes: Map<String, Outcome>) : RekeyResult()
    }

    /**
     * Re-keys the missing wallet [targetId] from its recovery phrase. The
     * phrase must give that wallet's lock script at its derivation path, or
     * nothing is written. Every other missing wallet the phrase also matches
     * at its own derivation path (a parent and its sub-accounts) is restored
     * too, each through [restore] and verified on its own. Never creates a
     * wallet row and never touches a row that is not missing.
     */
    suspend fun rekeyFromPhrase(
        activity: FragmentActivity,
        targetId: String,
        words: List<String>,
        allowV1: Boolean,
    ): RekeyResult {
        if (!mnemonicManager.validateMnemonic(words)) return RekeyResult.InvalidInput
        val missing = missingWallets()
        val target = missing.firstOrNull { it.walletId == targetId } ?: return RekeyResult.NotMissing
        val seed = mnemonicManager.mnemonicToSeed(words)
        try {
            val targetKey = keyAtPath(seed, target)
            val matches = try {
                matchesWallet(targetKey, target)
            } finally {
                targetKey.fill(0)
            }
            if (!matches) {
                logger.w(TAG, "Phrase does not match $targetId; nothing written")
                return RekeyResult.NotThisWallet
            }
            val outcomes = linkedMapOf<String, Outcome>()
            for (wallet in listOf(target) + missing.filter { it.walletId != targetId }) {
                if (activity.isFinishing || activity.isDestroyed) {
                    outcomes[wallet.walletId] = Outcome.Failed("screen closed")
                    break
                }
                val key = keyAtPath(seed, wallet)
                try {
                    if (wallet !== target && !matchesWallet(key, wallet)) continue
                    // The row keeps its own type. Only a root phrase wallet stores
                    // the phrase: sub-accounts store none, and a raw-key wallet
                    // (which may be account 0 of a phrase) stays a raw key.
                    val storesPhrase = wallet.type == KeyManager.WALLET_TYPE_MNEMONIC && wallet.parentWalletId == null
                    val material = KeyMaterial(
                        privateKey = key.toHexStringNoPrefix(),
                        mnemonic = if (storesPhrase) words.joinToString(" ") else null,
                        walletType = wallet.type,
                        // The user just entered the phrase.
                        mnemonicBackedUp = storesPhrase,
                    )
                    outcomes[wallet.walletId] = restore(activity, wallet.walletId, material, allowV1)
                } finally {
                    key.fill(0)
                }
            }
            return RekeyResult.Done(outcomes)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Re-keys the missing raw-key wallet [targetId] from its private key,
     * which must give that wallet's lock script, or nothing is written.
     * The caller wipes [keyBytes].
     */
    suspend fun rekeyFromPrivateKey(
        activity: FragmentActivity,
        targetId: String,
        keyBytes: ByteArray,
        allowV1: Boolean,
    ): RekeyResult {
        if (keyBytes.size != PRIVATE_KEY_SIZE) return RekeyResult.InvalidInput
        val target = missingWallets().firstOrNull { it.walletId == targetId } ?: return RekeyResult.NotMissing
        if (!matchesWallet(keyBytes, target)) {
            logger.w(TAG, "Private key does not match $targetId; nothing written")
            return RekeyResult.NotThisWallet
        }
        val material = KeyMaterial(
            privateKey = keyBytes.toHexStringNoPrefix(),
            mnemonic = null,
            walletType = KeyManager.WALLET_TYPE_RAW_KEY,
            mnemonicBackedUp = false,
        )
        return RekeyResult.Done(mapOf(targetId to restore(activity, targetId, material, allowV1)))
    }

    private fun keyAtPath(seed: ByteArray, wallet: WalletEntity): ByteArray {
        val (account, chain, index) = derivationIndices(wallet)
        return mnemonicManager.derivePrivateKey(seed, account, chain, index)
    }

    private fun materialMatches(material: KeyMaterial, wallet: WalletEntity): Boolean {
        val keyBytes = runCatching { material.privateKey.hexToByteArray() }.getOrNull()
            ?.takeIf { it.size == PRIVATE_KEY_SIZE } ?: return false
        return try {
            matchesWallet(keyBytes, wallet) && phraseMatchesKey(material.mnemonic, keyBytes, wallet)
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

    /**
     * A read-back result. [replaceable]: the row is proven unusable (does not
     * decrypt, device key invalidated, or holds another key), as opposed to
     * a cancel or a transient unlock error.
     */
    private class Checked(val outcome: Outcome, val replaceable: Boolean)

    private suspend fun verify(
        activity: FragmentActivity,
        walletId: String,
        wallet: WalletEntity,
        writtenIv: ByteArray?,
    ): Outcome = verifyChecked(activity, walletId, wallet, writtenIv).outcome

    private suspend fun verifyChecked(
        activity: FragmentActivity,
        walletId: String,
        wallet: WalletEntity,
        writtenIv: ByteArray?,
    ): Checked {
        // The reader clears the unverified mark on any good decrypt (F-B-4);
        // remember it so a row holding another key keeps the mark.
        val wasUnverified = walletId in recoveryState.unverifiedWalletIds()
        val read = keyReader.readPrivateKey(
            activity = activity,
            walletId = walletId,
            promptTitle = "Check restored keys",
            promptSubtitle = "Use your phone's screen lock to confirm this wallet's keys were saved.",
        )
        val checked = when (read) {
            is WalletKeyReader.Result.Success -> try {
                if (matchesWallet(read.privateKey, wallet)) {
                    Checked(Outcome.Restored, replaceable = false)
                } else {
                    // A row holding another key is not verified.
                    if (wasUnverified) recoveryState.addUnverified(walletId)
                    Checked(Outcome.AddressMismatch, replaceable = true)
                }
            } finally {
                read.privateKey.fill(0)
            }
            // The row stays unverified; the next attempt reads it again.
            WalletKeyReader.Result.Cancelled -> return Checked(Outcome.Cancelled, replaceable = false)
            is WalletKeyReader.Result.AuthError ->
                Checked(Outcome.Failed("unlock error ${read.errorCode}"), replaceable = false)
            is WalletKeyReader.Result.NotAvailable ->
                Checked(Outcome.Failed("stored keys not readable"), replaceable = true)
            WalletKeyReader.Result.KeyInvalidated ->
                Checked(Outcome.Failed("device key invalidated"), replaceable = true)
        }
        if (checked.outcome == Outcome.Restored) {
            recoveryState.removeUnverified(walletId)
            logger.i(TAG, "Verified restored keys for $walletId")
            return checked
        }
        logger.w(TAG, "Read-back check failed for $walletId")
        if (writtenIv != null) {
            // Only the row written in this call, and only while it is still that row.
            runCatching { keyMaterialDao.deleteIfIv(walletId, writtenIv) }
                .onFailure { logger.e(TAG, "Removing the unverified row failed for $walletId", it) }
        }
        return checked
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
