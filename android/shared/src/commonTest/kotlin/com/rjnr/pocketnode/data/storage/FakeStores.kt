package com.rjnr.pocketnode.data.storage

/**
 * In-memory bindings for the four storage seams (M3 #3).
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
}

class FakeTransactionStore(
    /** Hex block numbers per (walletId, network), as the cache stores them. */
    val blockNumbers: MutableMap<Pair<String, String>, List<String>> = mutableMapOf(),
) : TransactionStore {

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        blockNumbers[walletId to network] ?: emptyList()
}
