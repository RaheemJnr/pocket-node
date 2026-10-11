package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.wallet.WalletRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes every wallet from this app and clears the app PIN, so the next
 * screen can start as a fresh install. Used by the Recovery screen's "Start
 * fresh with a recovery phrase" step, when the app PIN cannot be verified.
 *
 * Only the app's own data is touched: each wallet goes through
 * [WalletRepository.factoryReset] (the per-wallet delete path: its Room rows
 * and its key material), then the remaining Room caches are cleared, and the
 * PIN goes last, and only once no wallet is left, so a failure part-way
 * never leaves wallets behind without a PIN. Funds stay on chain.
 */
@Singleton
class LocalWalletReset @Inject constructor(
    private val walletRepository: WalletRepository,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager,
    private val pinManager: PinManager,
) {
    suspend fun removeAllWalletsAndPin() {
        walletRepository.factoryReset()
        // factoryReset logs and skips a wallet it cannot delete. Keep the PIN
        // in that case: wallets must never stay behind without it.
        val left = walletRepository.walletCount()
        check(left == 0) { "$left wallet(s) could not be removed" }
        cacheManager.clearAll()
        daoSyncManager.clearAll()
        // force: the PIN-encrypted backups belong to the wallets just removed.
        pinManager.removePin(force = true)
    }
}
