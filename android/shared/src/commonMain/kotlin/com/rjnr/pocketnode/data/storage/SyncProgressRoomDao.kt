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

    /** Every row recorded for [network], in no particular order. */
    @Query("SELECT * FROM sync_progress WHERE network = :network")
    suspend fun getAllForNetwork(network: String): List<SyncProgressRow>

    /**
     * Atomic update of the registration metadata only, preserving
     * `localSavedBlockNumber`. Returns rows affected (0 = no row, caller falls
     * back to [upsert]).
     *
     * The statement is character for character the Android app's
     * `SyncProgressDao.updateLightStart`: the two tables have the same shape,
     * and the row count it returns is the contract
     * [SyncProgressStore.updateLightStart] promises its callers.
     */
    @Query("UPDATE sync_progress SET lightStartBlockNumber = :lightStart, updatedAt = :ts WHERE walletId = :walletId AND network = :network")
    suspend fun updateLightStart(walletId: String, network: String, lightStart: Long, ts: Long): Int

    /**
     * Atomic update of progress only, preserving `lightStartBlockNumber`.
     * Returns rows affected (0 = no row, caller falls back to [upsert]).
     *
     * Again character for character the Android `SyncProgressDao.updateLocalSaved`.
     */
    @Query("UPDATE sync_progress SET localSavedBlockNumber = :block, updatedAt = :ts WHERE walletId = :walletId AND network = :network")
    suspend fun updateLocalSaved(walletId: String, network: String, block: Long, ts: Long): Int
}
