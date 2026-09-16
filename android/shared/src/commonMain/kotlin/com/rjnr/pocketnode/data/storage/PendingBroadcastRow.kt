package com.rjnr.pocketnode.data.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Broadcast-state row for an in-flight or recently-failed transaction.
 *
 * All ten columns of the Android `PendingBroadcastEntity`, same names, same
 * types, same index. Distinct from `transactions`: that table is the
 * user-facing ledger record (kept forever); this one is the ephemeral
 * broadcast state machine.
 *
 * State transitions (CAS in [PendingBroadcastRoomDao.compareAndUpdateState]):
 *   BROADCASTING -> BROADCAST     (the bridge returned the hash)
 *   BROADCASTING -> CONFIRMED     (the watchdog saw it on chain)
 *   BROADCASTING -> FAILED        (the bridge refused it; or null x 3 past the tip window)
 *   BROADCAST    -> CONFIRMED     (the watchdog saw it on chain)
 *   BROADCAST    -> FAILED        (null x 3 past the tip window)
 *
 * [PendingBroadcastRecord] now carries the same ten columns (M3 #5), so the
 * mapping in both directions is field for field and nothing outside this file
 * needs to know which of the two it is holding.
 */
@Entity(
    tableName = "pending_broadcasts",
    indices = [
        Index(value = ["walletId", "network", "state"], name = "idx_pb_wallet_net_state")
    ]
)
data class PendingBroadcastRow(
    @PrimaryKey val txHash: String,
    val walletId: String,
    val network: String,
    val signedTxJson: String,
    val reservedInputs: String,         // JSON-encoded List<OutPoint>
    val state: String,                  // BROADCASTING | BROADCAST | CONFIRMED | FAILED
    val submittedAtTipBlock: Long,
    val nullCount: Int,
    val createdAt: Long,
    val lastCheckedAt: Long,
) {
    fun toRecord(): PendingBroadcastRecord = PendingBroadcastRecord(
        txHash = txHash,
        state = state,
        reservedInputs = reservedInputs,
        signedTxJson = signedTxJson,
        walletId = walletId,
        network = network,
        submittedAtTipBlock = submittedAtTipBlock,
        nullCount = nullCount,
        createdAt = createdAt,
        lastCheckedAt = lastCheckedAt,
    )

    companion object {
        fun from(record: PendingBroadcastRecord): PendingBroadcastRow = PendingBroadcastRow(
            txHash = record.txHash,
            walletId = record.walletId,
            network = record.network,
            signedTxJson = record.signedTxJson,
            reservedInputs = record.reservedInputs,
            state = record.state,
            submittedAtTipBlock = record.submittedAtTipBlock,
            nullCount = record.nullCount,
            createdAt = record.createdAt,
            lastCheckedAt = record.lastCheckedAt,
        )
    }
}
