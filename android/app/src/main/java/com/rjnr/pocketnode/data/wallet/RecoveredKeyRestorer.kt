package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes key material recovered from a PIN-encrypted backup (Recovery
 * screen, "Recover with PIN") back into the normal key store.
 *
 * Per wallet:
 * 1. The wallet must already have a Room wallet row. A backup without one is
 *    skipped ([Outcome.NoWalletRow]): recreating the row would need the
 *    import path's naming, activation and sync-mode choices, so the backup
 *    file stays on disk for a later phrase restore instead.
 * 2. The backup's private key must derive the row's stored addresses, or
 *    nothing is written ([Outcome.AddressMismatch]).
 * 3. The bundle is persisted through [WalletKeyWriter], the same writer a
 *    fresh import uses: auth-bound V2 when the device has a biometric or a
 *    screen lock, otherwise the V1 software key. Like every other wallet
 *    write, V1 needs the user's consent first (the "Continue without a
 *    device lock?" dialog): without `allowV1` nothing is written and the
 *    result is [Outcome.NeedsNoLockConsent]. An existing `key_material` row
 *    is never overwritten; it is only verified.
 * 4. The stored key is read back through [WalletKeyReader] and must derive
 *    the stored addresses again. A row written here that fails this check is
 *    removed, so a later attempt starts clean.
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
    private val logger: Logger,
) {

    sealed class Outcome {
        /** Stored, read back and matched the wallet's addresses. */
        object Restored : Outcome()
        /** No Room wallet row for this backup; nothing written. */
        object NoWalletRow : Outcome()
        /**
         * The backup key, or a key already stored for the wallet, does not
         * derive the wallet's addresses. Nothing was overwritten.
         */
        object AddressMismatch : Outcome()
        /**
         * The phone has no screen lock and the user has not agreed to the
         * software-only key yet; nothing was written.
         */
        object NeedsNoLockConsent : Outcome()
        /** The user dismissed the unlock prompt; a retry can finish it. */
        object Cancelled : Outcome()
        /** Write or read-back failed; [reason] never holds key material. */
        data class Failed(val reason: String) : Outcome()
    }

    suspend fun restore(
        activity: FragmentActivity,
        walletId: String,
        material: KeyMaterial,
        allowV1: Boolean,
    ): Outcome {
        val wallet = walletDao.getById(walletId)
        if (wallet == null) {
            logger.w(TAG, "No wallet row for backup $walletId; skipped, backup kept")
            return Outcome.NoWalletRow
        }
        val keyBytes = runCatching { material.privateKey.hexToByteArray() }.getOrNull()
            ?.takeIf { it.size == PRIVATE_KEY_SIZE }
            ?: return Outcome.Failed("backup key is not a 32-byte key").also {
                logger.w(TAG, "Backup for $walletId does not hold a 32-byte key")
            }
        try {
            if (!derivesAddresses(keyBytes, wallet)) {
                logger.w(TAG, "Backup for $walletId does not match the wallet address; not written")
                return Outcome.AddressMismatch
            }

            val wroteRow = if (keyMaterialDao.getKdfVersion(walletId) != null) {
                // Written by an earlier attempt, or never lost: verify only.
                false
            } else {
                if (needsNoLockConsent() && !allowV1) {
                    logger.i(TAG, "No screen lock and no consent yet for $walletId; not written")
                    return Outcome.NeedsNoLockConsent
                }
                when (val written = write(activity, walletId, keyBytes, material)) {
                    WalletKeyWriter.Result.Success -> true
                    WalletKeyWriter.Result.Cancelled -> return Outcome.Cancelled
                    is WalletKeyWriter.Result.AuthError ->
                        return Outcome.Failed("unlock error ${written.errorCode}")
                    WalletKeyWriter.Result.KeyInvalidated -> return Outcome.Failed("device key invalidated")
                    WalletKeyWriter.Result.NoSecureLock -> return Outcome.Failed("no secure lock")
                    is WalletKeyWriter.Result.WriteFailed -> {
                        logger.e(TAG, "Writing restored keys failed for $walletId", written.cause)
                        return Outcome.Failed("write failed")
                    }
                }
            }

            return verify(activity, walletId, wallet, wroteRow)
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * True when a restore would store keys at V1 (no biometric, no screen
     * lock), which needs the no-lock consent first.
     */
    fun needsNoLockConsent(): Boolean =
        !authManager.isBiometricEnrolled() && !authManager.hasDeviceCredential()

    /** Clears the startup corruption flag; call only once every wallet is [Outcome.Restored]. */
    @Suppress("DEPRECATION")
    fun clearCorruptionFlag() {
        keyManager.resetCorruptionFlag()
    }

    private suspend fun write(
        activity: FragmentActivity,
        walletId: String,
        keyBytes: ByteArray,
        material: KeyMaterial,
    ): WalletKeyWriter.Result {
        // Same bundle format a fresh import produces.
        val bundle = WalletDerivation.encodePlaintextBundle(keyBytes, material.mnemonic?.split(" "))
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
        wroteRow: Boolean,
    ): Outcome {
        val read = keyReader.readPrivateKey(
            activity = activity,
            walletId = walletId,
            promptTitle = "Check restored keys",
            promptSubtitle = "Use your phone's screen lock to confirm this wallet's keys were saved.",
        )
        val outcome = when (read) {
            is WalletKeyReader.Result.Success -> try {
                if (derivesAddresses(read.privateKey, wallet)) Outcome.Restored else Outcome.AddressMismatch
            } finally {
                read.privateKey.fill(0)
            }
            // The row is in place; a retry verifies it without writing again.
            WalletKeyReader.Result.Cancelled -> return Outcome.Cancelled
            is WalletKeyReader.Result.AuthError -> Outcome.Failed("unlock error ${read.errorCode}")
            is WalletKeyReader.Result.NotAvailable -> Outcome.Failed("stored keys not readable")
            WalletKeyReader.Result.KeyInvalidated -> Outcome.Failed("device key invalidated")
        }
        if (outcome != Outcome.Restored) {
            logger.w(TAG, "Read-back check failed for $walletId")
            if (wroteRow) {
                // Only a row written in this call is removed; the backup file stays.
                runCatching { keyMaterialDao.delete(walletId) }
                    .onFailure { logger.e(TAG, "Removing the unverified row failed for $walletId", it) }
            }
        } else {
            logger.i(TAG, "Restored keys for $walletId from backup")
        }
        return outcome
    }

    private fun derivesAddresses(privateKey: ByteArray, wallet: WalletEntity): Boolean {
        val info = runCatching { WalletDerivation.walletInfo(privateKey) }.getOrNull() ?: return false
        return info.mainnetAddress == wallet.mainnetAddress && info.testnetAddress == wallet.testnetAddress
    }

    private companion object {
        const val TAG = "RecoveredKeyRestorer"
        const val PRIVATE_KEY_SIZE = 32
    }
}
