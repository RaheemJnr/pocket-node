package com.rjnr.pocketnode.data.storage

import androidx.room.Entity

/**
 * Per-wallet, per-network sync checkpoint.
 *
 * Column names, types and the composite primary key deliberately mirror the Android app's
 * `SyncProgressEntity`. Keeping the two shapes convergent is what lets M3 move the real
 * store into shared code later without inventing a migration for the Android side.
 */
@Entity(
    tableName = "sync_progress",
    primaryKeys = ["walletId", "network"]
)
data class SyncProgressRow(
    val walletId: String,
    val network: String,                 // NetworkType.name (MAINNET / TESTNET)
    val lightStartBlockNumber: Long,     // block we passed to nativeSetScripts
    val localSavedBlockNumber: Long,     // last block fully processed
    val updatedAt: Long                  // epoch ms
)
