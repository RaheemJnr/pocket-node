package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallet_vault")
data class WalletVaultEntity(
    @PrimaryKey val walletId: String,
    val generation: Long,
    val profileGeneration: Long,
    val envelope: ByteArray,
    val cleanupPending: Boolean,
) {
    override fun toString() = "WalletVaultEntity([redacted])"
}
