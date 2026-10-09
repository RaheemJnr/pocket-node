package com.rjnr.pocketnode.data.storage

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The shared-core database.
 *
 * Deliberately separate from the Android app's `AppDatabase` (`pocket_node.db`, schema v15):
 * during M3 only iOS opens this one, so the Android schema is untouched and needs no migration.
 *
 * ## Versions
 *
 * v1: `sync_progress` only (the Room KMP spike).
 *
 * v2: adds `transactions`, `pending_broadcasts` and `balance_cache`, the three tables
 * the activity list and the balance read work from. Three pure additions with no change to
 * `sync_progress`, so an `@AutoMigration` covers it: Room generates the three `CREATE TABLE`
 * statements and their indices from the exported v1 and v2 schema JSON. A device that
 * installed the v1 build keeps the checkpoints it has already recorded rather than
 * resyncing from its mode's start block.
 */
@Database(
    entities = [
        SyncProgressRow::class,
        TransactionRow::class,
        PendingBroadcastRow::class,
        BalanceCacheRow::class,
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(PocketNodeCoreDatabaseConstructor::class)
abstract class PocketNodeCoreDatabase : RoomDatabase() {
    abstract fun syncProgress(): SyncProgressRoomDao
    abstract fun transactions(): TransactionRoomDao
    abstract fun pendingBroadcasts(): PendingBroadcastRoomDao
    abstract fun balanceCache(): BalanceCacheRoomDao
}

/**
 * The one `expect` declaration permitted in the shared module.
 *
 * "Prefer interfaces + DI over expect/actual" is the module's standing rule, but Room KMP
 * leaves no choice here: the compiler plugin generates the `actual object` per target, and
 * `@ConstructedBy` requires the expect side to be written by hand. There is no interface
 * seam that avoids it, and no hand-written `actual` accompanies this declaration, which is
 * exactly why the two suppressions below are needed.
 *
 * See https://developer.android.com/kotlin/multiplatform/room#defining-database
 */
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object PocketNodeCoreDatabaseConstructor : RoomDatabaseConstructor<PocketNodeCoreDatabase> {
    override fun initialize(): PocketNodeCoreDatabase
}
