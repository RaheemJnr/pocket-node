package com.rjnr.pocketnode.data.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

/**
 * One cached transaction, as the shared-core database stores it.
 *
 * Column names and types mirror the Android app's `TransactionEntity` field for
 * field, including the `fee_shannons` column name and the `''` default on
 * `walletId`. Keeping the two shapes convergent is what lets M4 move the
 * Android app onto this database without inventing a translation layer.
 *
 * The one deliberate difference is the primary key. Android keys on `txHash`
 * alone; the shared table keys on (`txHash`, `network`), so the same hash seen
 * on both networks cannot collide in a single file.
 *
 * Every statement in [TransactionRoomDao] is network-scoped with one
 * exception, and the exception is worth naming rather than glossing.
 * `updateStatusOnly` matches on the hash alone, because the seam it serves
 * ([TransactionStore.updateTransactionStatus]) takes a hash and a status and
 * nothing else: the broadcast watchdog that calls it holds no network. On this
 * database that is harmless in practice for two reasons. iOS opens one file
 * per network (`SyncService` puts it under the network's own data directory),
 * so a second row for the same hash cannot exist in the file being written;
 * and a CKB transaction hash commits to its inputs, which are cells that exist
 * on one chain, so the same hash appearing on both mainnet and testnet is not
 * a thing that happens outside a deliberately crafted collision. If the seam
 * ever gains a network parameter, narrow the statement with it.
 */
@Entity(
    tableName = "transactions",
    primaryKeys = ["txHash", "network"],
    indices = [
        Index(
            value = ["walletId", "network", "timestamp"],
            name = "idx_tx_wallet_network_time",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]
        )
    ]
)
data class TransactionRow(
    val txHash: String,
    val blockNumber: String,
    val blockHash: String,
    val timestamp: Long,
    val balanceChange: String,
    val direction: String,
    val fee: String,
    val confirmations: Int,
    val blockTimestampHex: String?,
    val network: String,
    val status: String,      // "PENDING", "CONFIRMED", "FAILED"
    val isLocal: Boolean,    // true = broadcast but not yet seen on chain
    val cachedAt: Long,
    @ColumnInfo(defaultValue = "''") val walletId: String = "",
    // Null means "fee not known yet", NOT zero: see TransactionRecord.feeShannons.
    @ColumnInfo(name = "fee_shannons") val feeShannons: Long? = null,
) {
    fun toRecord(): TransactionRecord = TransactionRecord(
        txHash = txHash,
        blockNumber = blockNumber,
        blockHash = blockHash,
        timestamp = timestamp,
        balanceChange = balanceChange,
        direction = direction,
        fee = fee,
        confirmations = confirmations,
        blockTimestampHex = blockTimestampHex,
        isDaoRelated = direction.startsWith("dao_"),
        status = status,
        feeShannons = feeShannons,
    )
}

/** One row of [TransactionRoomDao.getKnownFees]. */
data class TxFeeRow(val txHash: String, val feeShannons: Long)
