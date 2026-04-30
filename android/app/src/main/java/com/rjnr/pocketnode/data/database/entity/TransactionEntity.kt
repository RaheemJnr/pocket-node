package com.rjnr.pocketnode.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

@Entity(
    tableName = "transactions",
    indices = [
        Index(
            value = ["walletId", "network", "timestamp"],
            name = "idx_tx_wallet_network_time",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]
        ),
        // MIGRATION_5_6 created this index (originally as a partial index keyed
        // on PENDING rows). Room does not see WHERE clauses via the pragmas it
        // uses for schema validation, so declaring it here as a regular @Index
        // matches what Room observes in the migrated schema. Fresh installs get
        // the regular form; both forms have identical query-planner shape on
        // the (walletId, network, timestamp DESC) prefix the activity sort
        // uses. Without this declaration, Room flags the index as an unexpected
        // extra and crashes on launch after migration. (#90 v1.5.1 hotfix)
        Index(
            value = ["walletId", "network", "timestamp"],
            name = "idx_tx_pending",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]
        )
    ]
)
data class TransactionEntity(
    @PrimaryKey val txHash: String,
    val blockNumber: String,
    val blockHash: String,
    val timestamp: Long,
    val balanceChange: String,
    val direction: String,
    val fee: String,
    val confirmations: Int,
    val blockTimestampHex: String?,
    val network: String,
    val status: String,       // "PENDING", "CONFIRMED", "FAILED"
    val isLocal: Boolean,     // true = broadcast but not yet synced
    val cachedAt: Long,
    // MIGRATION_2_3 added this column with `DEFAULT ''`. Without the
    // matching @ColumnInfo annotation, Room expects no default and crashes
    // schema validation after migration. (v1.5.1 hotfix)
    @ColumnInfo(defaultValue = "''") val walletId: String = ""
) {
    fun toTransactionRecord(): TransactionRecord = TransactionRecord(
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
        status = status
    )

    companion object {
        fun fromTransactionRecord(
            txHash: String,
            blockNumber: String,
            blockHash: String,
            timestamp: Long,
            balanceChange: String,
            direction: String,
            fee: String,
            confirmations: Int,
            blockTimestampHex: String?,
            network: String,
            walletId: String = ""
        ): TransactionEntity = TransactionEntity(
            txHash = txHash,
            blockNumber = blockNumber,
            blockHash = blockHash,
            timestamp = timestamp,
            balanceChange = balanceChange,
            direction = direction,
            fee = fee,
            confirmations = confirmations,
            blockTimestampHex = blockTimestampHex,
            network = network,
            status = if (confirmations > 0) "CONFIRMED" else "PENDING",
            isLocal = false,
            cachedAt = System.currentTimeMillis(),
            walletId = walletId
        )
    }
}
