package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

/**
 * The two storage seams the single-wallet iOS profile has nothing behind.
 *
 * [SyncCoordinator][com.rjnr.pocketnode.data.gateway.SyncCoordinator] takes all
 * four seams because Android needs all four. iOS in M3 has one wallet, no
 * sub-account discovery (#82/#382) and no cached transaction table yet, so two
 * of them have no data to answer from. Empty answers are the honest ones
 * rather than a stub that lies: with no candidates the coordinator registers
 * exactly the wallet's own script, and with no cached transactions the
 * candidate scan-start falls back to the mode-derived historical block, which
 * is the same value it would reach on a wallet with no history.
 *
 * Android never uses either: it binds both seams to real Room DAOs.
 */

/** No sub-account discovery candidates, and nowhere to record a registration. */
object EmptySubAccountCandidateStore : SubAccountCandidateStore {

    override suspend fun getForParent(parentId: String): List<SubAccountCandidateRecord> =
        emptyList()

    override suspend fun updateRegisteredFrom(
        parentId: String,
        derivationPath: String,
        fromBlock: Long,
    ) = Unit

    /**
     * No candidates, so no candidate scripts. The gap-limit signature check
     * reads this as "no sister address of ours could have taken the change",
     * which is the truth on a profile that derives one address.
     */
    override suspend fun allScriptArgs(): List<String> = emptyList()
}

/**
 * No cached transaction table yet, so no history to read back and nowhere to
 * write one.
 *
 * The write members are no-ops rather than throwing. The interface's own
 * contract says [TransactionStore.updateTransactionStatus] propagates so the
 * broadcast watchdog can leave a pending row recoverable, but there is no row
 * to lose here: nothing is ever written, so nothing can be silently dropped.
 * Throwing would instead break the callers for a store that is simply absent.
 */
object EmptyTransactionStore : TransactionStore {

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        emptyList()

    override suspend fun cacheTransactions(
        records: List<TransactionRecord>,
        network: String,
        walletId: String,
    ) = Unit

    override suspend fun getPendingNotIn(
        network: String,
        excludeHashes: Set<String>,
        walletId: String,
    ): List<TransactionRecord> = emptyList()

    override suspend fun getOrphanPendingHashes(
        walletId: String,
        network: String,
    ): List<String> = emptyList()

    override suspend fun updateTransactionStatus(hash: String, status: String) = Unit
}
