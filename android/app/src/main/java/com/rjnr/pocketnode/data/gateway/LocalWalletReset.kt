package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.RecoveryStateStore
import com.rjnr.pocketnode.data.wallet.WalletRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Verified installation reset shared by Forgot PIN and Recovery. The PIN is removed last. */
@Singleton
class LocalWalletReset @Inject constructor(
    private val walletRepository: WalletRepository,
    private val keyManager: KeyManager,
    private val keyMaterialDao: KeyMaterialDao,
    private val keyBackupManager: KeyBackupManager,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager,
    private val pinManager: PinManager,
    private val recoveryState: RecoveryStateStore,
) {
    enum class Startup { READY, RESTART, RETRY }

    /** Called before legacy probing or normal startup routing. Never interprets a corrupt marker as consent. */
    suspend fun prepareStartup(): Startup {
        val boundary = walletRepository.resetBoundary
        return try {
            when (boundary.status()) {
                com.rjnr.pocketnode.data.wallet.WalletStorageBoundary.Status.NONE -> if (boundary.isClosed()) Startup.RETRY else Startup.READY
                com.rjnr.pocketnode.data.wallet.WalletStorageBoundary.Status.INVALID -> Startup.RETRY
                com.rjnr.pocketnode.data.wallet.WalletStorageBoundary.Status.PENDING -> {
                    removeAllWalletsAndPin()
                    Startup.RESTART
                }
                com.rjnr.pocketnode.data.wallet.WalletStorageBoundary.Status.COMPLETE -> {
                    if (!boundary.canFinishAtStartup()) Startup.RESTART
                    else {
                        verifyEmpty()
                        check(!pinManager.hasPin()) { "PIN reset is incomplete." }
                        boundary.finishCompletedStartup()
                        Startup.READY
                    }
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Startup.RETRY }
    }

    private suspend fun verifyEmpty() {
        walletRepository.verifyResetEmpty()
        check(keyMaterialDao.count() == 0) { "Wallet key records remain." }
        check(!keyBackupManager.hasAnyBackups()) { "Wallet backup copies remain." }
    }

    suspend fun removeAllWalletsAndPin() {
        walletRepository.factoryReset()
        keyBackupManager.deleteAllChecked()
        cacheManager.clearAll()
        daoSyncManager.clearAll()
        verifyEmpty()
        pinManager.removePinDurablyForReset()
        recoveryState.clear()
        walletRepository.resetBoundary.completeReset()
    }
}
