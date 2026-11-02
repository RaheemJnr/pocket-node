package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Nonsecret deletion intent, committed atomically with wallet removal and retained until cleanup. */
@Entity(tableName = "wallet_pending_cleanup")
data class WalletPendingCleanupEntity(
    @PrimaryKey val walletId: String,
    val keepBackup: Boolean,
)
