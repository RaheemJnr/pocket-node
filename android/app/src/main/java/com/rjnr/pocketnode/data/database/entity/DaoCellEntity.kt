package com.rjnr.pocketnode.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "dao_cells",
    primaryKeys = ["txHash", "index"],
    indices = [
        Index(
            value = ["walletId", "network"],
            name = "idx_dao_wallet_network"
        )
    ]
)
data class DaoCellEntity(
    val txHash: String,
    val index: String,
    val capacity: Long,
    val status: String,
    val depositBlockNumber: Long,
    val depositBlockHash: String,
    val depositEpochHex: String?,
    val withdrawBlockNumber: Long?,
    val withdrawBlockHash: String?,
    val withdrawEpochHex: String?,
    val compensation: Long,
    val unlockEpochHex: String?,
    val depositTimestamp: Long,
    val network: String,
    val lastUpdatedAt: Long,
    // MIGRATION_2_3 created this column with `DEFAULT ''`. (v1.5.1 hotfix)
    @ColumnInfo(defaultValue = "''") val walletId: String = ""
)
