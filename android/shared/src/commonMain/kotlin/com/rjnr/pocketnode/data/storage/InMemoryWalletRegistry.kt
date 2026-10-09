package com.rjnr.pocketnode.data.storage

import kotlin.concurrent.Volatile

/**
 * [WalletRegistry] held in memory, written from outside.
 *
 * The single-wallet iOS profile for M3. iOS keeps its one wallet's metadata in
 * a JSON file (`Services/Wallet/WalletStore.swift`), not in a table the shared
 * code can query, and Swift cannot implement [WalletRegistry.allWallets]
 * itself: it is a `suspend` member, and a Swift type cannot satisfy one. So the
 * direction is inverted. Swift pushes its wallet in through [set] and the
 * registry answers the sync code's reads from that list.
 *
 * The list is replaced wholesale rather than mutated, so a `@Volatile`
 * reference is the whole of the thread safety: a reader sees either the
 * previous list or the next one, never a half-written one.
 *
 * Android never uses this. `RoomWalletRegistry` reads the real `wallets`
 * table, which is what multi-wallet needs and what iOS gets in M4.
 */
class InMemoryWalletRegistry : WalletRegistry {

    @Volatile
    private var records: List<WalletRecord> = emptyList()

    /** Replace the registry's contents. The single-wallet caller passes one record. */
    fun set(records: List<WalletRecord>) {
        this.records = records.toList()
    }

    override suspend fun allWallets(): List<WalletRecord> = records
}
