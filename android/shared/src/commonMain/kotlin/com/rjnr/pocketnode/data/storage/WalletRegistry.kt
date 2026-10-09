package com.rjnr.pocketnode.data.storage

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The wallet identity the shared sync code reads: which wallets exist, how to
 * recover each one's lock script from a cached address, and how recently each
 * was used.
 *
 * Deliberately narrower than the Android `WalletEntity`: only the columns
 * `SyncCoordinator` actually reads are here. A consumer that needs the name,
 * colour, type or derivation path is a UI or key-management consumer and
 * belongs on the app-side wallet repository, not on this seam.
 *
 * Exported to Swift as `SharedWalletRecord`: the iOS app already owns a
 * `WalletRecord` struct (its single-wallet metadata file, `WalletStore.swift`),
 * and two types of that name in scope make every unqualified mention of it
 * ambiguous. The Kotlin name is unchanged, so Android sees nothing of this.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("SharedWalletRecord")
data class WalletRecord(
    val walletId: String,
    val mainnetAddress: String,
    val testnetAddress: String,
    /** Epoch ms of the last activation; the multi-wallet cap sorts on it. */
    val lastActiveAt: Long,
)

/**
 * The `wallets` table as the shared sync code sees it.
 *
 * Android binds it to `WalletDao` through `RoomWalletRegistry`; iOS will bind
 * it to its own wallet store.
 */
interface WalletRegistry {

    /**
     * Every wallet, in the store's natural order. Callers that care about
     * recency sort by [WalletRecord.lastActiveAt] themselves.
     */
    suspend fun allWallets(): List<WalletRecord>
}
