package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * In-flight DAO phase-2 unlock, keyed by the WITHDRAWING cell's outpoint (#529).
 *
 * The mirror image of [PendingDaoWithdrawEntity], for the other half of the
 * DAO lifecycle. Between broadcasting the unlock and the light client
 * indexing the spend, the withdrawing cell still scans as UNLOCKABLE: the
 * card kept offering "Unlock" and a second tap was accepted while the first
 * transaction was in flight. This row is the durable marker that overlays
 * the cell's status as UNLOCKING until the unlock commits (the cell is
 * consumed, so the cached dao_cells row is retired COMPLETED) or fails (the
 * cell reverts to UNLOCKABLE). Persisted, so a relaunch mid-unlock does not
 * lose it.
 */
@Entity(
    tableName = "pending_dao_unlocks",
    primaryKeys = ["withdrawingTxHash", "withdrawingIndex"],
    indices = [Index(value = ["walletId", "network"], name = "idx_pending_unlock_wallet_network")]
)
data class PendingDaoUnlockEntity(
    val withdrawingTxHash: String,
    val withdrawingIndex: String,
    val unlockTxHash: String,
    val walletId: String,
    val network: String,
    val createdAt: Long,
)
