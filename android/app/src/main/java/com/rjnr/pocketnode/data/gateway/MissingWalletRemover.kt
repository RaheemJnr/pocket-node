package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.data.wallet.RecoveryStateStore
import com.rjnr.pocketnode.data.wallet.WalletRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Removes one wallet whose keys are lost from this phone, from the Recovery
 * screen. Only a wallet still missing keys can be removed here; a wallet
 * with keys never is.
 *
 * Uses the normal per-wallet delete path ([WalletRepository.deleteWallet]:
 * its rows, its key row if any, its sync progress and caches), but keeps its
 * PIN-encrypted backup file. If it is the active wallet, another remaining
 * wallet is made active first (one with keys, a top-level one preferred),
 * as the wallet settings delete does. The only wallet on the phone is not
 * removed.
 */
@Singleton
class MissingWalletRemover @Inject constructor(
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository,
    private val keyRestorer: RecoveredKeyRestorer,
    private val recoveryState: RecoveryStateStore,
    private val logger: Logger,
) {

    enum class Result { REMOVED, REMOVED_CLEANUP_PENDING, NOT_MISSING, ONLY_WALLET }

    suspend fun remove(walletId: String): Result {
        val missingIds = keyRestorer.missingWallets().map { it.walletId }.toSet()
        if (walletId !in missingIds) return Result.NOT_MISSING
        val all = walletRepository.getAll()
        val wallet = all.firstOrNull { it.walletId == walletId } ?: return Result.NOT_MISSING
        val others = all.filter { it.walletId != walletId }
        if (others.isEmpty()) return Result.ONLY_WALLET

        // Once the switch or the delete commits, the rest must follow even if
        // the screen goes away mid-way, as in the wallet settings delete.
        var cleanupPending = false
        withContext(NonCancellable) {
            if (wallet.isActive || walletRepository.activeWalletIdSnapshot() == walletId) {
                val next = others.firstOrNull { it.parentWalletId == null && it.walletId !in missingIds }
                    ?: others.firstOrNull { it.walletId !in missingIds }
                    ?: others.firstOrNull { it.parentWalletId == null }
                    ?: others.first()
                walletRepository.switchActiveWallet(next.walletId)
            }
            try {
                walletRepository.deleteWallet(walletId, keepBackupFile = true)
            } catch (_: com.rjnr.pocketnode.data.wallet.WalletCleanupPendingException) {
                cleanupPending = true
            }
            recoveryState.removeUnverified(walletId)
            try {
                gatewayRepository.onWalletDeleted(walletId)
            } catch (e: Exception) {
                logger.w(TAG, "Re-registration after removing $walletId failed", e)
            }
        }
        logger.i(TAG, "Removed wallet $walletId from Recovery; its backup file is kept")
        return if (cleanupPending) Result.REMOVED_CLEANUP_PENDING else Result.REMOVED
    }

    private companion object {
        const val TAG = "MissingWalletRemover"
    }
}
