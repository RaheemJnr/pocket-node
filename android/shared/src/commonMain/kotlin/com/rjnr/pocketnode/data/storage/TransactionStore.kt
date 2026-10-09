package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

/**
 * The cached-transaction reads and writes the shared sync and read paths
 * perform.
 *
 * Android binds it to `TransactionDao` and `CacheManager` through
 * `RoomTransactionStore`, and the members do NOT share one failure contract:
 *
 *  - [cacheTransactions] and [getPendingNotIn] swallow and log, so a cache
 *    hiccup degrades to "nothing written" / "nothing pending" rather than
 *    failing the read that called them;
 *  - [getBlockNumbers] and [getOrphanPendingHashes] propagate, because they
 *    are plain queries with no fallback answer that would be honest;
 *  - [updateTransactionStatus] propagates on purpose. The broadcast watchdog
 *    calls it before the terminal CAS precisely so a database failure leaves
 *    the pending row recoverable; swallowing here would silently break that.
 */
interface TransactionStore {

    /**
     * Block numbers of every cached transaction for a wallet, as the
     * `0x`-prefixed hex strings they are stored in. Order is unspecified;
     * callers reduce the list themselves.
     */
    suspend fun getBlockNumbers(walletId: String, network: String): List<String>

    /**
     * Upsert a complete history walk. Implementations carry a previously
     * cached fee forward for any record whose own fee could not be resolved.
     */
    suspend fun cacheTransactions(
        records: List<TransactionRecord>,
        network: String,
        walletId: String,
    )

    /**
     * Local PENDING and FAILED rows for the wallet that are not in
     * [excludeHashes]. The history read merges these into the light client's
     * confirmed-only feed.
     */
    suspend fun getPendingNotIn(
        network: String,
        excludeHashes: Set<String>,
        walletId: String,
    ): List<TransactionRecord>

    /**
     * Hashes of PENDING rows with no `pending_broadcasts` entry: rows that
     * predate that table and which the watchdog therefore cannot see (#115).
     * Propagates a store failure; the cold-start reconcile wraps the call.
     */
    suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String>

    /**
     * Move one row to [status]. Propagates a store failure rather than
     * swallowing it: see the class KDoc for why the watchdog depends on that.
     */
    suspend fun updateTransactionStatus(hash: String, status: String)

    /**
     * Write the optimistic activity row for a broadcast that has just been
     * (or is about to be) submitted.
     *
     * [balanceChange] and [fee] are POSITIVE `0x`-prefixed hex; [direction]
     * ("in" / "out") carries the sign for the UI. [feeShannons] is the planned
     * fee as a number, null when it could not be resolved, in which case the
     * detail sheet reads "Pending" rather than a wrong figure (#497).
     *
     * Swallows a store failure, exactly as `CacheManager` does: a missing
     * activity row degrades the UI until the next history walk, while throwing
     * here would abort a send whose transaction is otherwise ready.
     */
    suspend fun insertPendingTransaction(
        txHash: String,
        network: String,
        walletId: String,
        balanceChange: String,
        direction: String,
        fee: String,
        feeShannons: Long?,
    )

    /**
     * Drop the activity row for [txHash]. Swallows a store failure for the
     * same reason [insertPendingTransaction] does: every caller is already on
     * a failure path and has a more useful error to report.
     */
    suspend fun deleteTransaction(txHash: String)

    /**
     * The cached row's direction and recorded fee for [txHash] on [network],
     * or null when there is no row (public #538). A retry reads these before
     * [deleteTransaction] so a re-sent "self" row keeps its label and fee.
     * Propagates a store failure; the one caller wraps it. The default
     * answers null, which only costs a retried self row its label.
     */
    suspend fun cachedDirectionAndFee(txHash: String, network: String): Pair<String, Long?>? = null
}
