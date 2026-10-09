package com.rjnr.pocketnode.data.storage

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Builds the iOS-side [PocketNodeCoreDatabase] builder.
 *
 * [path] is an absolute file path, not a bare name: on iOS Room does no directory resolution
 * of its own, so the caller decides where the file lives (Application Support for real data,
 * the temporary directory for tests).
 */
fun iosDatabaseBuilder(path: String): RoomDatabase.Builder<PocketNodeCoreDatabase> =
    Room.databaseBuilder<PocketNodeCoreDatabase>(name = path)

/**
 * Opens the database at [path], ready to use.
 *
 * This is where the bundled SQLite driver is chosen: it compiles SQLite into the binary, which
 * Kotlin/Native needs because there is no framework SQLite to link against. The dependency is
 * scoped to `iosMain` so it never reaches the Android APK.
 */
fun createIosPocketNodeCoreDatabase(
    path: String,
    queryContext: CoroutineContext = Dispatchers.Default,
): PocketNodeCoreDatabase =
    createPocketNodeCoreDatabase(iosDatabaseBuilder(path), BundledSQLiteDriver(), queryContext)
