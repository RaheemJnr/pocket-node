package com.rjnr.pocketnode.data.storage

/**
 * An in-flight, recently-failed or freshly-inserted broadcast.
 *
 * Carries every column the Android `PendingBroadcastEntity` declares. The
 * read path only ever touched four of them; the send path and the
 * broadcast watchdog own the bookkeeping columns too
 * ([submittedAtTipBlock], [nullCount], [createdAt], [lastCheckedAt]), so the
 * record now mirrors the row one-for-one and the Room adapter is a pure
 * field-for-field mapping in both directions.
 *
 * [state] carries the same strings the entity declares; the constants below
 * repeat them so shared code can compare without reaching into the app module.
 *
 * No field carries a default, deliberately. [walletId] and [network] are the
 * key [PendingBroadcastStore.getActive] filters on, so a caller that forgot
 * one would build a row the reservation filter cannot see, and the next send
 * would happily select inputs an in-flight broadcast already spent.
 */
data class PendingBroadcastRecord(
    val txHash: String,
    val state: String,
    /** JSON-encoded `List<OutPoint>` reserved by this broadcast. */
    val reservedInputs: String,
    val signedTxJson: String,
    val walletId: String,
    val network: String,
    /** Light-client tip when the broadcast was submitted; the watchdog's timeout origin. */
    val submittedAtTipBlock: Long,
    /** Consecutive "transaction unknown" answers from the node. */
    val nullCount: Int,
    val createdAt: Long,
    val lastCheckedAt: Long,
) {
    companion object {
        const val STATE_BROADCASTING = "BROADCASTING"
        const val STATE_BROADCAST = "BROADCAST"
        const val STATE_CONFIRMED = "CONFIRMED"
        const val STATE_FAILED = "FAILED"
    }
}

/**
 * The `pending_broadcasts` reads and writes the shared send path and broadcast
 * watchdog perform.
 *
 * Android binds it to `PendingBroadcastDao` through `RoomPendingBroadcastStore`.
 * The observable Flows the activity UI collects stay on the Room DAO: they are
 * an Android-only surface and nothing in the shared engine reads them.
 */
interface PendingBroadcastStore {

    /**
     * Snapshot of the BROADCASTING and BROADCAST rows for a wallet on a
     * network. Terminal rows (CONFIRMED, FAILED) are not returned.
     */
    suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRecord>

    /**
     * Upsert one row, replacing any row with the same `txHash`. The DAO
     * declares `OnConflictStrategy.REPLACE`, and the send path depends on it:
     * the hash-mismatch re-key path deletes and re-inserts under the returned
     * hash, and a retry re-inserts the same hash after dropping its FAILED row.
     */
    suspend fun insert(record: PendingBroadcastRecord)

    /**
     * Move [hash] from [expected] to [next], stamping `lastCheckedAt` with
     * [now]. Answers the number of rows changed: 1 on success, 0 when the row
     * is gone or already moved on. The watchdog and the broadcast path both
     * race on the same row, so the count is the only honest way to tell a win
     * from a loss.
     */
    suspend fun compareAndUpdateState(hash: String, expected: String, next: String, now: Long): Int

    /** Record [count] consecutive unknown-transaction answers for [hash]. */
    suspend fun updateNullCount(hash: String, count: Int, now: Long)

    /** Drop [hash]'s row. A no-op when there is none. */
    suspend fun delete(hash: String)

    /** The FAILED row for [hash], or null when there is none. Retry reads its signed bytes. */
    suspend fun getFailedRow(hash: String): PendingBroadcastRecord?
}
