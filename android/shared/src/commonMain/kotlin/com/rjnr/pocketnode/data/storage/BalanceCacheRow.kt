package com.rjnr.pocketnode.data.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse

/**
 * Last spendable balance computed for a wallet on a network, so a screen can
 * paint a number before the light client answers.
 *
 * Column names and types mirror the Android `BalanceCacheEntity`, including the
 * `''` default on `walletId` and the `blockNumber` column that holds the
 * response's `asOfBlock`.
 */
@Entity(
    tableName = "balance_cache",
    primaryKeys = ["walletId", "network"]
)
data class BalanceCacheRow(
    @ColumnInfo(defaultValue = "''") val walletId: String,
    val network: String,
    val address: String,
    val capacity: String,
    val capacityCkb: String,
    val blockNumber: String,
    val cachedAt: Long,
) {
    fun toResponse(): BalanceResponse = BalanceResponse(
        address = address,
        capacity = capacity,
        capacityCkb = capacityCkb,
        asOfBlock = blockNumber,
    )

    companion object {
        fun from(
            response: BalanceResponse,
            network: String,
            walletId: String,
            cachedAt: Long,
        ): BalanceCacheRow = BalanceCacheRow(
            walletId = walletId,
            network = network,
            address = response.address,
            capacity = response.capacity,
            capacityCkb = response.capacityCkb,
            blockNumber = response.asOfBlock,
            cachedAt = cachedAt,
        )
    }
}

/** Reads and writes [BalanceCacheRow]. One row per (walletId, network). */
@Dao
interface BalanceCacheRoomDao {

    @Upsert
    suspend fun upsert(row: BalanceCacheRow)

    @Query("SELECT * FROM balance_cache WHERE walletId = :walletId AND network = :network")
    suspend fun getByWalletAndNetwork(walletId: String, network: String): BalanceCacheRow?
}
