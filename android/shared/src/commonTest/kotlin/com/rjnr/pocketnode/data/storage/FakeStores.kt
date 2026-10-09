package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

/**
 * In-memory bindings for the storage seams.
 *
 * They hold the same semantics the Room queries behind them do: the
 * `updateLightStart` row count, the candidate keep-min on
 * `registeredFromBlock`, and the account-index ordering `getForParent`
 * promises. A test that depends on any of those gets the production answer
 * without a database.
 */
class FakeSyncProgressStore(
    initial: List<SyncProgressRecord> = emptyList(),
) : SyncProgressStore {

    /** Every row, keyed the way the composite primary key keys them. */
    val rows: MutableMap<Pair<String, String>, SyncProgressRecord> =
        initial.associateBy { it.walletId to it.network }.toMutableMap()

    override suspend fun getAllForNetwork(network: String): List<SyncProgressRecord> =
        rows.values.filter { it.network == network }

    override suspend fun upsert(record: SyncProgressRecord) {
        rows[record.walletId to record.network] = record
    }

    override suspend fun updateLightStart(
        walletId: String,
        network: String,
        lightStart: Long,
        ts: Long,
    ): Int {
        val key = walletId to network
        val existing = rows[key] ?: return 0
        rows[key] = existing.copy(lightStartBlockNumber = lightStart, updatedAt = ts)
        return 1
    }

    override suspend fun updateLocalSaved(
        walletId: String,
        network: String,
        block: Long,
        ts: Long,
    ): Int {
        val key = walletId to network
        val existing = rows[key] ?: return 0
        rows[key] = existing.copy(localSavedBlockNumber = block, updatedAt = ts)
        return 1
    }
}

class FakeWalletRegistry(
    /** Returned verbatim by [allWallets]; mutate to change what the sync sees. */
    val wallets: MutableList<WalletRecord> = mutableListOf(),
) : WalletRegistry {

    override suspend fun allWallets(): List<WalletRecord> = wallets.toList()
}

class FakeSubAccountCandidateStore(
    initial: List<SubAccountCandidateRecord> = emptyList(),
) : SubAccountCandidateStore {

    /** Every candidate, keyed by the (parent, path) primary key. */
    val candidates: MutableMap<Pair<String, String>, SubAccountCandidateRecord> =
        initial.associateBy { it.parentWalletId to it.derivationPath }.toMutableMap()

    override suspend fun getForParent(parentId: String): List<SubAccountCandidateRecord> =
        candidates.values.filter { it.parentWalletId == parentId }.sortedBy { it.accountIndex }

    override suspend fun updateRegisteredFrom(
        parentId: String,
        derivationPath: String,
        fromBlock: Long,
    ) {
        val key = parentId to derivationPath
        val existing = candidates[key] ?: return
        // Keep-min, exactly the DAO's `registeredFromBlock = 0 OR > :fromBlock` guard.
        if (existing.registeredFromBlock == 0L || existing.registeredFromBlock > fromBlock) {
            candidates[key] = existing.copy(registeredFromBlock = fromBlock)
        }
    }

    override suspend fun allScriptArgs(): List<String> = candidates.values.map { it.scriptArgs }
}

class FakeTransactionStore(
    /** Hex block numbers per (walletId, network), as the cache stores them. */
    val blockNumbers: MutableMap<Pair<String, String>, List<String>> = mutableMapOf(),
) : TransactionStore {

    /** Everything [cacheTransactions] was handed, per (walletId, network). */
    val cached: MutableMap<Pair<String, String>, List<TransactionRecord>> = mutableMapOf()

    /** Local non-confirmed rows per (walletId, network); [getPendingNotIn] filters these. */
    val pending: MutableMap<Pair<String, String>, List<TransactionRecord>> = mutableMapOf()

    /** Orphan PENDING hashes per (walletId, network). */
    val orphanPending: MutableMap<Pair<String, String>, List<String>> = mutableMapOf()

    /** Every [updateTransactionStatus] call, oldest first. */
    val statusWrites: MutableList<Pair<String, String>> = mutableListOf()

    /** One optimistic activity row, as [insertPendingTransaction] was handed it. */
    data class PendingInsert(
        val txHash: String,
        val network: String,
        val walletId: String,
        val balanceChange: String,
        val direction: String,
        val fee: String,
        val feeShannons: Long?,
    )

    /** Every [insertPendingTransaction] call, oldest first. */
    val pendingInserts: MutableList<PendingInsert> = mutableListOf()

    /** Every hash handed to [deleteTransaction], oldest first. */
    val deletedHashes: MutableList<String> = mutableListOf()

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        blockNumbers[walletId to network] ?: emptyList()

    override suspend fun cacheTransactions(
        records: List<TransactionRecord>,
        network: String,
        walletId: String,
    ) {
        cached[walletId to network] = records
    }

    override suspend fun getPendingNotIn(
        network: String,
        excludeHashes: Set<String>,
        walletId: String,
    ): List<TransactionRecord> =
        (pending[walletId to network] ?: emptyList()).filter { it.txHash !in excludeHashes }

    override suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String> =
        orphanPending[walletId to network] ?: emptyList()

    override suspend fun updateTransactionStatus(hash: String, status: String) {
        statusWrites += hash to status
    }

    override suspend fun insertPendingTransaction(
        txHash: String,
        network: String,
        walletId: String,
        balanceChange: String,
        direction: String,
        fee: String,
        feeShannons: Long?,
    ) {
        pendingInserts += PendingInsert(
            txHash = txHash,
            network = network,
            walletId = walletId,
            balanceChange = balanceChange,
            direction = direction,
            fee = fee,
            feeShannons = feeShannons,
        )
    }

    override suspend fun deleteTransaction(txHash: String) {
        deletedHashes += txHash
        pendingInserts.removeAll { it.txHash == txHash }
    }
}

class FakeBalanceCache(
    /** Cached balances, keyed the way the composite primary key keys them. */
    val rows: MutableMap<Pair<String, String>, BalanceResponse> = mutableMapOf(),
) : BalanceCache {

    override suspend fun getCachedBalance(network: String, walletId: String): BalanceResponse? =
        rows[walletId to network]

    override suspend fun cacheBalance(
        response: BalanceResponse,
        network: String,
        walletId: String,
    ) {
        rows[walletId to network] = response
    }
}

class FakeHeaderCache(
    /** Cached headers by block hash. */
    val headers: MutableMap<String, JniHeaderView> = mutableMapOf(),
) : HeaderCache {

    /** The network each write was scoped to, oldest first. */
    val writtenNetworks: MutableList<String> = mutableListOf()

    override suspend fun get(blockHash: String): JniHeaderView? = headers[blockHash]

    override suspend fun put(header: JniHeaderView, network: String) {
        headers[header.hash] = header
        writtenNetworks += network
    }
}

/**
 * In-memory `pending_broadcasts`.
 *
 * Keyed by `txHash` the way the primary key keys it, so [insert] REPLACEs and
 * [compareAndUpdateState] answers the DAO's rows-affected count (1 when the
 * row was in the expected state, 0 otherwise). [rows] is kept as a view over
 * the same store for the suites written before the widening.
 */
class FakePendingBroadcastStore : PendingBroadcastStore {

    /** Every row, keyed by hash. */
    val byHash: MutableMap<String, PendingBroadcastRecord> = mutableMapOf()

    /**
     * Put [records] in the store as given.
     *
     * It does NOT stamp `walletId`/`network` onto them: those decide what
     * [getActive] returns, so a test that means to seed an active row has to
     * say which wallet and network it is active for, exactly as production
     * code does.
     */
    fun seed(vararg records: PendingBroadcastRecord) {
        records.forEach { byHash[it.txHash] = it }
    }

    override suspend fun getActive(
        walletId: String,
        network: String,
    ): List<PendingBroadcastRecord> = byHash.values.filter {
        it.walletId == walletId && it.network == network &&
            (it.state == PendingBroadcastRecord.STATE_BROADCASTING ||
                it.state == PendingBroadcastRecord.STATE_BROADCAST)
    }

    override suspend fun insert(record: PendingBroadcastRecord) {
        byHash[record.txHash] = record
    }

    override suspend fun compareAndUpdateState(
        hash: String,
        expected: String,
        next: String,
        now: Long,
    ): Int {
        val existing = byHash[hash] ?: return 0
        if (existing.state != expected) return 0
        byHash[hash] = existing.copy(state = next, lastCheckedAt = now)
        return 1
    }

    override suspend fun updateNullCount(hash: String, count: Int, now: Long) {
        val existing = byHash[hash] ?: return
        byHash[hash] = existing.copy(nullCount = count, lastCheckedAt = now)
    }

    override suspend fun delete(hash: String) {
        byHash.remove(hash)
    }

    override suspend fun getFailedRow(hash: String): PendingBroadcastRecord? =
        byHash[hash]?.takeIf { it.state == PendingBroadcastRecord.STATE_FAILED }
}
