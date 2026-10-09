package com.rjnr.pocketnode.data.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Hand-written stand-ins for the two Room DAOs the activity feed reads
 * through.
 *
 * They exist so the store logic that is NOT SQL, the fee carry-forward and the
 * direction filtering, can be tested in `commonTest` on every target. The SQL
 * itself is proved against a real database in `iosTest`, which is the only
 * place a Room implementation actually exists.
 *
 * The behaviour mirrored here is the behaviour the store depends on: REPLACE on
 * a duplicate key, the pending-first then timestamp-then-block-descending sort
 * with insertion order as the final tie-break, `getKnownFees` skipping nulls,
 * and the network scoping on every statement that has it.
 */
class FakeTransactionRoomDao : TransactionRoomDao {

    /** Insertion order, which stands in for the `rowid` tie-break. */
    val rows: MutableList<TransactionRow> = mutableListOf()

    /** Every batch handed to [insertAll], for asserting what was written. */
    val insertedBatches: MutableList<List<TransactionRow>> = mutableListOf()

    /** Every hash list handed to [getKnownFees], so chunking can be asserted. */
    val knownFeeQueries: MutableList<List<String>> = mutableListOf()

    override suspend fun getKnownFees(hashes: List<String>, network: String): List<TxFeeRow> {
        knownFeeQueries += hashes
        return rows
            .filter { it.network == network && it.txHash in hashes && it.feeShannons != null }
            .map { TxFeeRow(it.txHash, it.feeShannons!!) }
    }

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        rows.filter { it.walletId == walletId && it.network == network }.map { it.blockNumber }

    override suspend fun insert(row: TransactionRow) {
        replace(row)
    }

    override suspend fun insertAll(rows: List<TransactionRow>) {
        insertedBatches += rows
        rows.forEach { replace(it) }
    }

    override suspend fun getNonConfirmedByWallet(
        walletId: String,
        network: String,
    ): List<TransactionRow> = rows.filter {
        it.walletId == walletId && it.network == network && it.status in setOf("PENDING", "FAILED")
    }

    override suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String> =
        rows.filter { it.walletId == walletId && it.network == network && it.status == "PENDING" }
            .map { it.txHash }

    override suspend fun updateStatusOnly(hash: String, status: String, cachedAt: Long) {
        val index = rows.indexOfFirst { it.txHash == hash }
        if (index >= 0) rows[index] = rows[index].copy(status = status, cachedAt = cachedAt)
    }

    override suspend fun deleteByHash(hash: String, network: String) {
        rows.removeAll { it.txHash == hash && it.network == network }
    }

    override suspend fun deleteByHashOnAnyNetwork(hash: String) {
        rows.removeAll { it.txHash == hash }
    }

    override suspend fun getByHash(hash: String, network: String): TransactionRow? =
        rows.firstOrNull { it.txHash == hash && it.network == network }

    override suspend fun pageAll(
        walletId: String,
        network: String,
        limit: Int,
        offset: Int,
    ): List<TransactionRow> = sorted(walletId, network).drop(offset).take(limit)

    override suspend fun page(
        walletId: String,
        network: String,
        directions: List<String>,
        limit: Int,
        offset: Int,
    ): List<TransactionRow> = sorted(walletId, network)
        .filter { it.direction in directions }
        .drop(offset)
        .take(limit)

    private fun replace(row: TransactionRow) {
        val index = rows.indexOfFirst { it.txHash == row.txHash && it.network == row.network }
        if (index >= 0) rows[index] = row else rows += row
    }

    /**
     * PENDING first, then timestamp descending, then block number descending,
     * then insertion order.
     *
     * The block-number level is `LENGTH(blockNumber) DESC, blockNumber DESC`
     * in SQL; here it is the parsed value, which is the thing that SQL spelling
     * is an encoding of. `iosTest` proves the two agree on a real database.
     */
    private fun sorted(walletId: String, network: String): List<TransactionRow> =
        rows.withIndex()
            .filter { it.value.walletId == walletId && it.value.network == network }
            .sortedWith(
                compareBy<IndexedValue<TransactionRow>> { if (it.value.status == "PENDING") 0 else 1 }
                    .thenByDescending { it.value.timestamp }
                    .thenByDescending { it.value.blockNumber.removePrefix("0x").toLongOrNull(16) ?: 0L }
                    .thenBy { it.index }
            )
            .map { it.value }
}

/** In-memory stand-in for [PendingBroadcastRoomDao]. */
class FakePendingBroadcastRoomDao : PendingBroadcastRoomDao {

    private val state = MutableStateFlow<List<PendingBroadcastRow>>(emptyList())

    val rows: List<PendingBroadcastRow> get() = state.value

    /** Seed rows without going through [insert]. */
    fun seed(vararg rows: PendingBroadcastRow) {
        state.value = state.value + rows
    }

    override suspend fun insert(row: PendingBroadcastRow) {
        state.value = state.value.filterNot { it.txHash == row.txHash } + row
    }

    override suspend fun compareAndUpdateState(
        hash: String,
        expected: String,
        next: String,
        now: Long,
    ): Int {
        val target = state.value.firstOrNull { it.txHash == hash && it.state == expected }
            ?: return 0
        state.value = state.value.map {
            if (it.txHash == hash) target.copy(state = next, lastCheckedAt = now) else it
        }
        return 1
    }

    override suspend fun updateNullCount(hash: String, count: Int, now: Long) {
        state.value = state.value.map {
            if (it.txHash == hash) it.copy(nullCount = count, lastCheckedAt = now) else it
        }
    }

    override suspend fun delete(hash: String) {
        state.value = state.value.filterNot { it.txHash == hash }
    }

    override suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRow> =
        state.value.filter {
            it.walletId == walletId && it.network == network &&
                it.state in setOf("BROADCASTING", "BROADCAST")
        }

    override fun observeAll(walletId: String, network: String): Flow<List<PendingBroadcastRow>> =
        state.map { rows -> rows.filter { it.walletId == walletId && it.network == network } }

    override suspend fun getFailedRow(hash: String): PendingBroadcastRow? =
        state.value.firstOrNull { it.txHash == hash && it.state == "FAILED" }
}

/**
 * [RoomKmpTransactionStore.insertPendingTransaction] with the defaults the
 * seam cannot carry.
 *
 * `TransactionStore` spells out all seven parameters and a Kotlin override
 * may not restate default values, so the production method has none. Tests
 * care about one or two fields at a time; this puts the rest in one place
 * rather than at every call site.
 */
suspend fun RoomKmpTransactionStore.insertPending(
    txHash: String,
    network: String,
    walletId: String = "",
    balanceChange: String = "0x0",
    direction: String = "out",
    fee: String = "0x0",
    feeShannons: Long? = null,
) = insertPendingTransaction(txHash, network, walletId, balanceChange, direction, fee, feeShannons)
