package com.rjnr.pocketnode.data.storage

/** Read-only presentation seam. Hints never establish chain, spending or DAO authority. */
class HistoryRecoveryReader(private val dao: HistoryRecoveryRoomDao) {
    suspend fun job(walletId: String, network: String) = dao.job(walletId, network)
    fun observeJob(walletId: String, network: String) = dao.observeJob(walletId, network)
    fun observeArchive(walletId: String, network: String) = dao.observeArchive(walletId, network)
    suspend fun discoveryJobs(walletId: String, network: String) = dao.scripts(network).filter { it.parentWalletId == walletId }
    suspend fun archive(walletId: String, network: String) = dao.archive(walletId, network)
    suspend fun daoHints(walletId: String, network: String) = dao.daoHints(walletId, network)
}
