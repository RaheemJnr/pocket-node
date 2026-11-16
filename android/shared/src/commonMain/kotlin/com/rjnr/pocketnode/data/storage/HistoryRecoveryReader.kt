package com.rjnr.pocketnode.data.storage

/** Read-only presentation seam. Hints never establish chain, spending or DAO authority. */
class HistoryRecoveryReader(private val dao: HistoryRecoveryRoomDao) {
    fun observePresentation(walletId: String, network: String) = kotlinx.coroutines.flow.combine(
        dao.observeJob(walletId, network), dao.observeDisplayArchive(walletId, network),
        dao.observeDisplayDaoHints(walletId, network),
    ) { job, archive, hints ->
        com.rjnr.pocketnode.data.history.RecoveryPresentation.from(job, archive, hints)
    }

    suspend fun job(walletId: String, network: String) = dao.job(walletId, network)
    fun observeJob(walletId: String, network: String) = dao.observeJob(walletId, network)
    fun observeArchive(walletId: String, network: String) = dao.observeDisplayArchive(walletId, network)
    suspend fun discoveryJobs(walletId: String, network: String) = dao.scripts(network).filter { it.parentWalletId == walletId }
    suspend fun archive(walletId: String, network: String) = dao.displayArchive(walletId, network)
    suspend fun daoHints(walletId: String, network: String) = dao.displayDaoHints(walletId, network)
}
