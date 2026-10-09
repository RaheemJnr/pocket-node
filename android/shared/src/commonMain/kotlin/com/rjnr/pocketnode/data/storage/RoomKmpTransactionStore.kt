package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.CancellationException

/**
 * [TransactionStore] over the shared-core Room database.
 *
 * The iOS binding for the seam Android binds to `CacheManager` through
 * `RoomTransactionStore`, and it keeps that class's per-member failure
 * contract exactly: [cacheTransactions] and [getPendingNotIn] swallow and log,
 * [getBlockNumbers] and [getOrphanPendingHashes] propagate, and
 * [updateTransactionStatus] propagates on purpose so a database failure leaves
 * a pending row recoverable for the broadcast watchdog.
 *
 * The members below the interface are the send path's. They are public
 * here ahead of that work so its merge only has to add `override`.
 */
class RoomKmpTransactionStore(
    private val dao: TransactionRoomDao,
    private val logger: Logger = NoopLogger,
    private val clock: Clock = SystemClock,
) : TransactionStore {

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        dao.getBlockNumbers(walletId, network)

    /**
     * Upsert a complete history walk.
     *
     * The fee carry-forward is `CacheManager.cacheTransactions`'s, rule for
     * rule. The insert is REPLACE, so a confirmed row that could not resolve
     * its own fee would otherwise erase the planned fee written when the user
     * sent it: whenever any record arrives without a fee, the fees already
     * stored for the whole batch are read back in one chunked query and used
     * as the fallback. `record.feeShannons ?: known[hash]` and never the other
     * way round, so a resolved fee always wins over a remembered one.
     *
     * The chunking matters as much as the carry-forward. A complete walk hands
     * this method every transaction the wallet has ever made, so an unchunked
     * `IN (:hashes)` throws past SQLite's bound-variable limit, and the catch
     * below would swallow that and skip `insertAll` entirely, silently stopping
     * history caching.
     */
    override suspend fun cacheTransactions(
        records: List<TransactionRecord>,
        network: String,
        walletId: String,
    ) {
        try {
            val knownFees = if (records.any { it.feeShannons == null }) {
                knownFeesFor(records.map { it.txHash }, network)
            } else {
                emptyMap()
            }
            val now = clock.nowMs()
            val rows = records.map { record ->
                TransactionRow(
                    txHash = record.txHash,
                    blockNumber = record.blockNumber,
                    blockHash = record.blockHash,
                    timestamp = record.timestamp,
                    balanceChange = record.balanceChange,
                    direction = record.direction,
                    fee = record.fee,
                    confirmations = record.confirmations,
                    blockTimestampHex = record.blockTimestampHex,
                    network = network,
                    status = if (record.confirmations > 0) "CONFIRMED" else "PENDING",
                    isLocal = false,
                    cachedAt = now,
                    walletId = walletId,
                    feeShannons = record.feeShannons ?: knownFees[record.txHash],
                )
            }
            dao.insertAll(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to write transaction cache", e)
        }
    }

    override suspend fun getPendingNotIn(
        network: String,
        excludeHashes: Set<String>,
        walletId: String,
    ): List<TransactionRecord> = try {
        dao.getNonConfirmedByWallet(walletId, network)
            .filter { it.isLocal && it.txHash !in excludeHashes }
            .map { it.toRecord() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(TAG, "Failed to read pending transactions", e)
        emptyList()
    }

    override suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String> =
        dao.getOrphanPendingHashes(walletId, network)

    override suspend fun updateTransactionStatus(hash: String, status: String) {
        // Propagates on purpose: the watchdog calls this BEFORE the terminal
        // CAS so a database hiccup leaves the pending row recoverable.
        dao.updateStatusOnly(hash, status, clock.nowMs())
    }

    /**
     * Record a transaction this device just broadcast, before the light client
     * has ever seen it. `isLocal = true` is what makes [getPendingNotIn] merge
     * it into the confirmed-only feed.
     */
    override suspend fun insertPendingTransaction(
        txHash: String,
        network: String,
        walletId: String,
        balanceChange: String,
        direction: String,
        fee: String,
        feeShannons: Long?,
    ) {
        try {
            val now = clock.nowMs()
            dao.insert(
                TransactionRow(
                    txHash = txHash,
                    blockNumber = "",
                    blockHash = "",
                    timestamp = now,
                    balanceChange = balanceChange,
                    direction = direction,
                    fee = fee,
                    confirmations = 0,
                    blockTimestampHex = null,
                    network = network,
                    status = "PENDING",
                    isLocal = true,
                    cachedAt = now,
                    walletId = walletId,
                    feeShannons = feeShannons,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to cache pending tx", e)
        }
    }

    /**
     * Drop the row for [txHash] on every network.
     *
     * The seam takes a hash and nothing else, for the same reason
     * [updateTransactionStatus] does: the send path's failure branches hold a
     * hash and an error, not a network. iOS opens one database file per
     * network, so on this database "every network" is one row; see the
     * [TransactionRow] KDoc. [deleteTransaction] with an explicit network is
     * the narrow form, and is what the tests and any future multi-network file
     * should use.
     */
    override suspend fun deleteTransaction(txHash: String) {
        try {
            dao.deleteByHashOnAnyNetwork(txHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to delete transaction $txHash", e)
        }
    }

    /** Drop one row, scoped to its network. */
    suspend fun deleteTransaction(txHash: String, network: String) {
        try {
            dao.deleteByHash(txHash, network)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to delete transaction $txHash", e)
        }
    }

    /** One row by hash, for the retry path and for tests. */
    suspend fun getByHash(txHash: String, network: String): TransactionRecord? =
        dao.getByHash(txHash, network)?.toRecord()

    override suspend fun cachedDirectionAndFee(txHash: String, network: String): Pair<String, Long?>? =
        dao.getByHash(txHash, network)?.let { it.direction to it.feeShannons }

    // --- Paging, for the activity list ---

    /**
     * One page of the wallet's history. [directions] empty means no direction
     * filter. The sort is documented on [TransactionRoomDao.pageAll].
     */
    suspend fun page(
        walletId: String,
        network: String,
        directions: List<String>,
        limit: Int,
        offset: Int,
    ): List<TransactionRecord> =
        if (directions.isEmpty()) {
            dao.pageAll(walletId, network, limit, offset)
        } else {
            dao.page(walletId, network, directions, limit, offset)
        }.map { it.toRecord() }

    private suspend fun knownFeesFor(hashes: List<String>, network: String): Map<String, Long> =
        hashes.chunked(SQLITE_VARIABLE_CHUNK)
            .flatMap { dao.getKnownFees(it, network) }
            .associate { it.txHash to it.feeShannons }

    private companion object {
        const val TAG = "RoomKmpTransactionStore"

        /** SQLite's default bound-variable limit is 999. Android uses the same 900. */
        const val SQLITE_VARIABLE_CHUNK = 900
    }
}
