package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes every wallet and key from this app and clears the app PIN, so the
 * app can start again as a fresh install. Used by the Recovery screen's
 * "Start fresh with a recovery phrase" step, offered once the app PIN is
 * permanently locked.
 *
 * Only the app's own data is touched:
 *  - every wallet through [WalletRepository.factoryReset] (the per-wallet
 *    delete path: its Room rows and its key material),
 *  - every PIN-encrypted backup file,
 *  - the legacy single-wallet key store ([KeyManager.deleteWallet]),
 *  - the remaining Room caches.
 *
 * The PIN goes last, and only once nothing above is left: no wallet row, no
 * key_material row, no backup file and no legacy key. Otherwise this throws
 * and the PIN stays, so keys are never left behind without it.
 */
@Singleton
class LocalWalletReset @Inject constructor(
    private val walletRepository: WalletRepository,
    private val keyManager: KeyManager,
    private val keyMaterialDao: KeyMaterialDao,
    private val keyBackupManager: KeyBackupManager,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager,
    private val pinManager: PinManager,
) {
    suspend fun removeAllWalletsAndPin() {
        walletRepository.factoryReset()
        for (id in keyBackupManager.listBackupWalletIds()) keyBackupManager.deleteBackup(id)
        keyManager.deleteWallet()
        cacheManager.clearAll()
        daoSyncManager.clearAll()

        // factoryReset logs and skips a wallet or key it cannot delete.
        val wallets = walletRepository.walletCount()
        check(wallets == 0) { "$wallets wallet(s) could not be removed" }
        val keys = keyMaterialDao.count()
        check(keys == 0) { "$keys key record(s) could not be removed" }
        check(!keyBackupManager.hasAnyBackups()) { "Backup files could not be removed" }
        check(!keyManager.hasWallet()) { "The legacy key could not be removed" }

        // force: the PIN-encrypted backups were removed above.
        pinManager.removePin(force = true)
    }
}
