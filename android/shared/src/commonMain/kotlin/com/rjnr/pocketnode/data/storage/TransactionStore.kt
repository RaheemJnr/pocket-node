package com.rjnr.pocketnode.data.storage

/**
 * The cached-transaction reads the shared sync code performs.
 *
 * One member for now, the single query `SyncCoordinator` needs (M3 #3). The
 * next extraction widens this interface rather than adding a second
 * transaction seam beside it (M3 #4).
 */
interface TransactionStore {

    /**
     * Block numbers of every cached transaction for a wallet, as the
     * `0x`-prefixed hex strings they are stored in. Order is unspecified;
     * callers reduce the list themselves.
     */
    suspend fun getBlockNumbers(walletId: String, network: String): List<String>
}
