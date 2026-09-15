package com.rjnr.pocketnode.data.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Reads and writes [SyncProgressRow]. Every call suspends onto Room's query context. */
@Dao
interface SyncProgressRoomDao {

    /** Inserts the row, or replaces the existing one with the same (walletId, network). */
    @Upsert
    suspend fun upsert(row: SyncProgressRow)

    @Query("SELECT * FROM sync_progress WHERE walletId = :walletId AND network = :network")
    suspend fun get(walletId: String, network: String): SyncProgressRow?

    @Query("SELECT * FROM sync_progress ORDER BY walletId, network")
    suspend fun all(): List<SyncProgressRow>
}
