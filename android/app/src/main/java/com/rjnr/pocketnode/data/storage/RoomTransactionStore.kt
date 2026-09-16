package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.TransactionDao
import javax.inject.Inject
import javax.inject.Singleton

/** Room binding for the shared [TransactionStore] seam (M3 #3). */
@Singleton
class RoomTransactionStore @Inject constructor(
    private val dao: TransactionDao,
) : TransactionStore {

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        dao.getBlockNumbers(walletId, network)
}
