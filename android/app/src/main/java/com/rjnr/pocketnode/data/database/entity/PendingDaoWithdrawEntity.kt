package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * In-flight DAO phase-1 withdraw, keyed by the DEPOSIT cell's outpoint (#347).
 *
 * The light client only sees committed chain state, so while a withdraw tx is
 * in the mempool the deposit cell still scans as DEPOSITED + withdrawable —
 * the banner ("Withdrawing from DAO…") was in-memory only and the deposit
 * could be withdrawn twice. This row is the durable marker: it overlays the
 * deposit's status to WITHDRAWING until the tx commits (deposit cell consumed)
 * or fails (reverts to DEPOSITED). Persisted, so it survives process death.
 */
@Entity(
    tableName = "pending_dao_withdraws",
    primaryKeys = ["depositTxHash", "depositIndex"],
    indices = [Index(value = ["walletId", "network"], name = "idx_pending_withdraw_wallet_network")]
)
data class PendingDaoWithdrawEntity(
    val depositTxHash: String,
    val depositIndex: String,
    val withdrawTxHash: String,
    val walletId: String,
    val network: String,
    val createdAt: Long,
)
