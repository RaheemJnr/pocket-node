package com.rjnr.pocketnode.data.storage

/**
 * Per-wallet, per-network sync checkpoint as the sync code sees it.
 *
 * Mirrors the Android app's `SyncProgressEntity` field for field, and the
 * `sync_progress` table [SyncProgressRow] declares for the shared Room
 * database, so the iOS side can map one onto the other without inventing a
 * second shape.
 */
data class SyncProgressRecord(
    val walletId: String,
    val network: String,                 // NetworkType.name (MAINNET / TESTNET)
    val lightStartBlockNumber: Long,     // block we passed to setScripts
    val localSavedBlockNumber: Long,     // last block fully processed
    val updatedAt: Long,                 // epoch ms
)

/**
 * The `sync_progress` reads and writes the shared sync code performs, narrowed
 * to the three the registration path needs.
 *
 * Android binds it to `SyncProgressDao` through `RoomSyncProgressStore`; iOS
 * will bind it to the shared Room database's `SyncProgressRoomDao`. Keeping
 * the seam this narrow is what lets `SyncCoordinator` live in `commonMain`
 * without a Room dependency of its own.
 */
interface SyncProgressStore {

    /** Every recorded checkpoint for [network], in no particular order. */
    suspend fun getAllForNetwork(network: String): List<SyncProgressRecord>

    /** Insert or replace [record] wholesale. */
    suspend fun upsert(record: SyncProgressRecord)

    /**
     * Atomic update of the registration metadata only, preserving
     * `localSavedBlockNumber`. Returns rows affected: 0 means no row exists
     * yet and the caller falls back to [upsert]. Closes the read-then-write
     * race between script registration and the sync poll's progress writes.
     */
    suspend fun updateLightStart(
        walletId: String,
        network: String,
        lightStart: Long,
        ts: Long,
    ): Int

    /**
     * The mirror of [updateLightStart]: an atomic update of the progress only,
     * preserving `lightStartBlockNumber`. Returns rows affected, 0 meaning no
     * row exists yet and the caller falls back to [upsert].
     *
     * This is the write the sync poll makes on every tick, so it has to be the
     * one that cannot clobber a registration landing beside it. Android's
     * `GatewayRepository.setWalletSyncBlock` has always used the same statement
     * for the same reason.
     */
    suspend fun updateLocalSaved(
        walletId: String,
        network: String,
        block: Long,
        ts: Long,
    ): Int
}
