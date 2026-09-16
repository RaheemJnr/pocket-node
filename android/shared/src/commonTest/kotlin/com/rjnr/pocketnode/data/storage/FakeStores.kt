package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

/**
 * In-memory bindings for the storage seams (M3 #3, widened in #4).
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

class FakePendingBroadcastStore : PendingBroadcastStore {

    /** Active rows per (walletId, network), exactly what the DAO snapshot returns. */
    val rows: MutableMap<Pair<String, String>, List<PendingBroadcastRecord>> = mutableMapOf()

    override suspend fun getActive(
        walletId: String,
        network: String,
    ): List<PendingBroadcastRecord> = rows[walletId to network] ?: emptyList()
}
