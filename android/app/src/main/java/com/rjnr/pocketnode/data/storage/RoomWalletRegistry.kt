package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Room binding for the shared [WalletRegistry] seam (M3 #3). */
@Singleton
class RoomWalletRegistry @Inject constructor(
    private val dao: WalletDao,
) : WalletRegistry {

    override suspend fun allWallets(): List<WalletRecord> = dao.getAll().map { it.toRecord() }
}

private fun WalletEntity.toRecord() = WalletRecord(
    walletId = walletId,
    mainnetAddress = mainnetAddress,
    testnetAddress = testnetAddress,
    lastActiveAt = lastActiveAt,
)
