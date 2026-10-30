package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity

/** Durable obligation, never authorization. Disposable DAO cache rows have no FK to this table. */
@Entity(tableName = "dao_recovery_guards", primaryKeys = ["walletId", "network", "genesisHash", "transactionHash", "outputIndex"])
data class DaoRecoveryEntity(
    val walletId: String, val network: String, val genesisHash: String,
    val transactionHash: String, val outputIndex: Long,
    val capacity: Long, val depositBlockNumber: Long, val scriptJson: String,
    val withdrawBlockNumber: Long, val withdrawBlockHash: String,
    val storeGeneration: String, val runNonce: String, val proofVersion: Int,
)

/** Scheduler-owned budget; updates must be transactional before starting native work. */
@Entity(tableName = "dao_recovery_retry_budget", primaryKeys = ["walletId", "network", "genesisHash", "transactionHash", "outputIndex"])
data class DaoRecoveryRetryEntity(
    val walletId: String, val network: String, val genesisHash: String,
    val transactionHash: String, val outputIndex: Long,
    val attempts: Int, val cycleStartedAt: Long, val lastAttemptAt: Long,
    val nextEligibleAt: Long,
)
