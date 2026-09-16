package com.rjnr.pocketnode.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes [TransactionRow].
 *
 * Every statement here is the Android `TransactionDao` statement it replaces,
 * with `AND network = :network` added where the composite key needs it. The
 * paging queries are the exception: Android hands its rows to the Paging
 * library and iOS pages by offset, so [page] and [pageAll] spell out the
 * `LIMIT`/`OFFSET` that `PagingSource` would otherwise generate.
 */
@Dao
interface TransactionRoomDao {

    /**
     * Fees already known for the given hashes. [RoomKmpTransactionStore]
     * inserts with REPLACE, so a confirmed row that could not resolve its own
     * fee would otherwise wipe the planned fee written when the user sent it.
     * One query, not one per row.
     */
    @Query(
        "SELECT txHash, fee_shannons AS feeShannons FROM transactions " +
            "WHERE network = :network AND txHash IN (:hashes) AND fee_shannons IS NOT NULL"
    )
    suspend fun getKnownFees(hashes: List<String>, network: String): List<TxFeeRow>

    /** Hex block numbers of every cached tx for a wallet. Anchors the candidate scan depth. */
    @Query("SELECT blockNumber FROM transactions WHERE walletId = :walletId AND network = :network")
    suspend fun getBlockNumbers(walletId: String, network: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: TransactionRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TransactionRow>)

    /**
     * Pending + Failed local rows. The light client's feed is confirmed-only,
     * so non-confirmed activity has to be merged in from here.
     */
    @Query(
        "SELECT * FROM transactions " +
            "WHERE walletId = :walletId AND network = :network AND status IN ('PENDING', 'FAILED')"
    )
    suspend fun getNonConfirmedByWallet(walletId: String, network: String): List<TransactionRow>

    /** PENDING rows with no `pending_broadcasts` entry: rows the watchdog cannot see. */
    @Query(
        "SELECT txHash FROM transactions " +
            "WHERE walletId = :walletId AND network = :network AND status = 'PENDING' " +
            "AND txHash NOT IN (SELECT txHash FROM pending_broadcasts)"
    )
    suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String>

    /**
     * Status-only update. Bumps `cachedAt` so any staleness logic treats the
     * transition as fresh, matching the Android statement exactly.
     *
     * Deliberately NOT network-scoped, and the only statement here that is
     * not. [TransactionStore.updateTransactionStatus] takes a hash and a
     * status and nothing else, because the broadcast watchdog that calls it
     * holds neither a network nor a wallet. Narrowing the statement would mean
     * widening that seam, which belongs to the send path rather than here.
     * See the [TransactionRow] KDoc for why the blast radius is one row in
     * practice.
     */
    @Query("UPDATE transactions SET status = :status, cachedAt = :cachedAt WHERE txHash = :hash")
    suspend fun updateStatusOnly(hash: String, status: String, cachedAt: Long)

    @Query("DELETE FROM transactions WHERE txHash = :hash AND network = :network")
    suspend fun deleteByHash(hash: String, network: String)

    /**
     * The hash-only form, for [TransactionStore.deleteTransaction], whose
     * signature carries no network. See [TransactionRow] for why that is one
     * row in practice on this database.
     */
    @Query("DELETE FROM transactions WHERE txHash = :hash")
    suspend fun deleteByHashOnAnyNetwork(hash: String)

    @Query("SELECT * FROM transactions WHERE txHash = :hash AND network = :network")
    suspend fun getByHash(hash: String, network: String): TransactionRow?

    /**
     * One page of the unfiltered list.
     *
     * The sort starts as Android's, PENDING first then `timestamp` descending,
     * and then adds two levels Android does not have.
     *
     * The history walk writes `timestamp = 0` on every confirmed row: the light
     * client reports a block timestamp, not a wall clock, and that lands in
     * `blockTimestampHex`. So `timestamp DESC` separates the pending rows from
     * the rest and then stops discriminating, and everything after it is doing
     * the real ordering.
     *
     * `LENGTH(blockNumber) DESC, blockNumber DESC` is a numeric sort on a text
     * column. `blockNumber` is `"0x"` followed by lowercase hex with no leading
     * zeros, so a larger block always has a longer string, or the same length
     * and a lexicographically larger one. This is what actually puts newer
     * transactions first, and it is NOT invisible: it is what stops a row that
     * was re-cached late (a high `rowid`) from sorting above genuinely newer
     * blocks, which is what the previous `rowid`-only tie-break did.
     *
     * `rowid ASC` remains last, to make the order total. Two rows in the same
     * block are ordered by when they were first written, and offset paging
     * needs SOME total order or page 0 and page 1 can overlap or skip.
     */
    @Query(
        "SELECT * FROM transactions WHERE walletId = :walletId AND network = :network " +
            "ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC, " +
            "LENGTH(blockNumber) DESC, blockNumber DESC, rowid ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun pageAll(
        walletId: String,
        network: String,
        limit: Int,
        offset: Int,
    ): List<TransactionRow>

    /** One page of the list narrowed to [directions]. Same sort as [pageAll], for the same reasons. */
    @Query(
        "SELECT * FROM transactions WHERE walletId = :walletId AND network = :network " +
            "AND direction IN (:directions) " +
            "ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC, " +
            "LENGTH(blockNumber) DESC, blockNumber DESC, rowid ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun page(
        walletId: String,
        network: String,
        directions: List<String>,
        limit: Int,
        offset: Int,
    ): List<TransactionRow>
}
