package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.vault.*
import javax.inject.Inject
import javax.inject.Singleton

/** Validates external recovery against existing metadata, then commits verified V3 protection.
 * Missing and unusable rows remain intact until coordinator generation/source-CAS activation.
 * No app-PIN backup, unauthenticated fallback or IV-based recovery deletion is performed.
 */
@Singleton
class RecoveredKeyRestorer @Inject constructor(
    private val walletDao: WalletDao,
    private val keyMaterialDao: KeyMaterialDao,
    private val keyReader: WalletKeyReader,
    private val keyManager: KeyManager,
    private val mnemonicManager: MnemonicManager,
    private val recoveryState: RecoveryStateStore,
    private val keyBackupManager: KeyBackupManager,
    private val logger: Logger,
    private val coordinator: WalletVaultCoordinator,
    private val prompt: VaultPrompt,
    private val legacyRecovery: LegacyBackupRecovery,
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
        /** Compatibility only for task6 UI migration; V3 recovery never returns this outcome. */
        object NeedsNoLockConsent : Outcome()
        /** Verified protection exists, but retained sources still need authenticated cleanup. */
        object CleanupRequired : Outcome()
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

    /** Compatibility for the old UI; recovery always requires a verified wallet passphrase. */
    fun needsNoLockConsent(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun restore(
        activity: FragmentActivity,
        walletId: String,
        material: KeyMaterial?,
        allowV1: Boolean,
    ): Outcome {
        val wallet = walletDao.getById(walletId) ?: return Outcome.NoWalletRow
        // A row's mere presence is never evidence of successful authentication.
        if (material == null) return Outcome.NoBackup
        val normalized = if (material.mnemonic == null) material.copy(walletType = "raw_key") else material
        if (!WalletSecretValidator(mnemonicManager).validate(normalized, wallet))
            return Outcome.AddressMismatch
        recoveryState.addUnverified(walletId)
        recoveryState.markIncomplete()
        val capturedSource = keyMaterialDao.getByWalletId(walletId)
        var acknowledgment: LegacySourceAcknowledgment? = null
        var authentication: LegacySourceAuthentication? = null
        if (normalized.mnemonic != null && capturedSource?.kdfVersion == 2) {
            // Classify the exact old source before activation. A supplied seed does not prove
            // the contents or lost capabilities of an unreadable legacy source.
            when (val existing = keyReader.authenticateCapturedV2Source(activity, capturedSource)) {
                is WalletKeyReader.MaterialResult.Success -> try {
                    val previous = KeyMaterial(existing.privateKey.toHexStringNoPrefix(), existing.mnemonic,
                        existing.walletType, existing.mnemonicBackedUp)
                    if (!WalletSecretValidator(mnemonicManager).validate(previous, wallet) ||
                        previous.privateKey != normalized.privateKey ||
                        previous.mnemonic != null && previous.mnemonic != normalized.mnemonic)
                        return Outcome.Failed("The existing recovery capabilities do not match. Nothing was replaced.")
                    authentication = LegacySourceAuthentication(wallet, capturedSource, null)
                } finally { existing.privateKey.fill(0) }
                WalletKeyReader.MaterialResult.KeyInvalidated -> {
                    if (prompt.choose(activity, "Replace inaccessible legacy protection?",
                        "The old device key is permanently unavailable. This verified recovery phrase restores its matching wallet and derived accounts, but cannot prove that the inaccessible old source had no different recovery phrase or accounts. Continue only if you accept losing those inaccessible capabilities. Surviving old-PIN backups still require authentication and remain preserved.",
                        "Use verified recovery phrase", "Cancel") != true) return Outcome.Cancelled
                    acknowledgment = LegacySourceAcknowledgment(wallet, capturedSource, null)
                }
                WalletKeyReader.MaterialResult.Cancelled -> return Outcome.Cancelled
                else -> return Outcome.Failed("Existing source authentication needs a retry. Nothing was replaced.")
            }
        }
        if (normalized.mnemonic == null && capturedSource != null &&
            (wallet.type == KeyManager.WALLET_TYPE_MNEMONIC || capturedSource.kdfVersion == 2)) {
            if (keyMaterialDao.getKdfVersion(walletId) == 3) {
                when (val existing = coordinator.readForRecovery(activity, walletId)) {
                    is VaultAccessResult.Success -> {
                        recoveryState.removeUnverified(walletId)
                        return Outcome.Restored
                    }
                    VaultAccessResult.Cancelled -> return Outcome.Cancelled
                    is VaultAccessResult.Unavailable -> {
                        if (existing.kind !in setOf(
                            VaultFailureKind.MISSING_DEVICE_KEY,
                            VaultFailureKind.INVALIDATED_DEVICE_KEY,
                            VaultFailureKind.NO_DEVICE_WRAP,
                            VaultFailureKind.NO_DEVICE_LOCK,
                        )) return Outcome.Failed("Existing wallet authentication needs a retry; nothing was replaced.")
                        when (prompt.choose(activity, "Preserve existing recovery phrase",
                            "The device route is unavailable. Your independent wallet passphrase may still recover the seed. External-key-only recovery cannot restore an inaccessible seed or other accounts.",
                            "Use wallet passphrase", "Recover spending only")) {
                            true -> return when (coordinator.read(activity, walletId, "Recover existing seed", "Enter its wallet passphrase",
                                VaultReadPreference.PASSPHRASE)) {
                                is VaultAccessResult.Success -> { recoveryState.removeUnverified(walletId); Outcome.Restored }
                                VaultAccessResult.Cancelled -> Outcome.Cancelled
                                VaultAccessResult.CleanupRequired -> Outcome.CleanupRequired
                                else -> Outcome.Failed("Passphrase authentication or cleanup did not complete. Nothing was replaced.")
                            }
                            false -> if (!confirmSpendingOnly(activity)) return Outcome.Cancelled
                            null -> return Outcome.Cancelled
                        }
                    }
                    VaultAccessResult.NoVault -> {
                        // Only the known empty V3 migration marker can be replaced without a vault.
                        // Coordinator activation compares this exact source again transactionally.
                        if (capturedSource.kdfVersion != 3 || capturedSource.encryptedPrivateKey.isNotEmpty() ||
                            capturedSource.encryptedMnemonic != null || capturedSource.iv.isNotEmpty())
                            return Outcome.Failed("Existing source is not an empty wallet marker.")
                        if (!confirmSpendingOnly(activity)) return Outcome.Cancelled
                    }
                    VaultAccessResult.CleanupRequired -> return Outcome.CleanupRequired
                    else -> return Outcome.Failed("Existing seed authentication did not complete.")
                }
            } else when (val existing = keyReader.readKeyMaterial(activity, walletId, "Preserve recovery phrase",
                "Authenticate existing wallet keys before private-key-only recovery.")) {
                is WalletKeyReader.MaterialResult.Success -> {
                    try {
                        // The reader validates both addresses and completes verified protection/cleanup.
                        recoveryState.removeUnverified(walletId)
                        return Outcome.Restored
                    } finally { existing.privateKey.fill(0) }
                }
                WalletKeyReader.MaterialResult.Cancelled -> return Outcome.Cancelled
                WalletKeyReader.MaterialResult.KeyInvalidated -> {
                    if (capturedSource.kdfVersion != 2) return Outcome.Failed("The existing source could not be classified safely; nothing was replaced.")
                    if (!confirmSpendingOnly(activity)) return Outcome.Cancelled
                    acknowledgment = LegacySourceAcknowledgment(wallet, capturedSource, null)
                }
                else -> return Outcome.Failed("Existing seed authentication did not complete. Retry or resolve legacy backup cleanup; nothing was replaced.")
            }
        }
        return when (acknowledgment?.let { coordinator.recoverWithAcknowledgedSource(activity, walletId, normalized, it) }
            ?: authentication?.let { coordinator.recoverWithAuthenticatedSource(activity, walletId, normalized, it) }
            ?: coordinator.recoverExisting(activity, walletId, normalized)) {
            is VaultAccessResult.Success -> {
                recoveryState.removeUnverified(walletId)
                Outcome.Restored
            }
            VaultAccessResult.Cancelled -> Outcome.Cancelled
            VaultAccessResult.CleanupRequired ->
                Outcome.CleanupRequired
            else -> Outcome.Failed("Wallet authentication or verification failed")
        }
    }

    private suspend fun confirmSpendingOnly(activity: FragmentActivity): Boolean = prompt.choose(activity,
        "Recover spending only?",
        "This private key restores spending for this wallet only. It does not restore an inaccessible recovery phrase or other accounts. Any readable seed is retained; surviving backups still require verified cleanup.",
        "Recover spending only", "Cancel") == true

    /** Native old-PIN prompt and all decrypted backup material stay inside the foreground operation. */
    suspend fun restoreLegacyBackup(activity: FragmentActivity, walletId: String): Outcome = restoreLegacySources(activity, walletId, false)

    /** Explicit task6 retry route for saved protection whose exact legacy archive still needs cleanup. */
    suspend fun resumeLegacyCleanup(activity: FragmentActivity, walletId: String): Outcome = restoreLegacySources(activity, walletId, true)

    private suspend fun restoreLegacySources(activity: FragmentActivity, walletId: String, resume: Boolean): Outcome {
        if (walletDao.getById(walletId) == null) return Outcome.NoWalletRow
        recoveryState.addUnverified(walletId)
        recoveryState.markIncomplete()
        return when (if (resume) legacyRecovery.resumeCleanup(activity, walletId) else legacyRecovery.recover(activity, walletId)) {
            is VaultAccessResult.Success -> {
                recoveryState.removeUnverified(walletId)
                Outcome.Restored
            }
            VaultAccessResult.Cancelled -> Outcome.Cancelled
            VaultAccessResult.CleanupRequired -> Outcome.CleanupRequired
            else -> Outcome.Failed(if (resume) "Legacy source authentication or cleanup did not complete. Sources remain preserved; retry authentication." else "No matching backup authenticated. Keep this wallet read-only and retry the old app PIN or external recovery secret.")
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
                        walletType = if (storesPhrase) KeyManager.WALLET_TYPE_MNEMONIC else KeyManager.WALLET_TYPE_RAW_KEY,
                        // The user just entered the phrase.
                        mnemonicBackedUp = storesPhrase,
                    )
                    val outcome = restore(activity, wallet.walletId, material, allowV1)
                    outcomes[wallet.walletId] = outcome
                    if (outcome == Outcome.Cancelled) break
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

    private fun matchesWallet(privateKey: ByteArray, wallet: WalletEntity): Boolean {
        val info = runCatching { WalletDerivation.walletInfo(privateKey) }.getOrNull() ?: return false
        return wallet.mainnetAddress == info.mainnetAddress && wallet.testnetAddress == info.testnetAddress
    }

    private fun derivationIndices(wallet: WalletEntity): Triple<Int, Int, Int> {
        val match = wallet.derivationPath?.let { PATH.matchEntire(it.trim()) }
            ?: return Triple(wallet.accountIndex, 0, 0)
        return Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }

    private companion object {
        const val TAG = "RecoveredKeyRestorer"
        const val PRIVATE_KEY_SIZE = 32
        val PATH = Regex("""m/44'/309'/(\d+)'/(\d+)/(\d+)""")
    }
}
