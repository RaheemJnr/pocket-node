package com.rjnr.pocketnode.data.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The shared-core database.
 *
 * Deliberately separate from the Android app's `AppDatabase` (`pocket_node.db`, schema v15):
 * during M3 only iOS opens this one, so the Android schema is untouched and needs no migration.
 */
@Database(entities = [SyncProgressRow::class], version = 1)
@ConstructedBy(PocketNodeCoreDatabaseConstructor::class)
abstract class PocketNodeCoreDatabase : RoomDatabase() {
    abstract fun syncProgress(): SyncProgressRoomDao
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
