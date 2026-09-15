package com.rjnr.pocketnode.data.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Finishes a platform-supplied [RoomDatabase.Builder].
 *
 * Both platform-specific pieces are parameters rather than decisions made here:
 *
 * [builder], because it needs a file path, and on Android a Context.
 *
 * [driver], because the driver implementations are per-platform artifacts. iOS passes
 * `BundledSQLiteDriver` (Kotlin/Native has no framework SQLite to fall back on); Android will
 * pass `AndroidSQLiteDriver` from the framework when it converges on this database in M4.
 * Keeping the bundled driver out of `commonMain` is what stops its ~2.5 MB of `libsqliteJni.so`
 * shipping in the APK for a database the Android app does not yet open.
 *
 * [queryContext] defaults to [Dispatchers.Default] rather than the `Dispatchers.IO` Room's own
 * KMP samples use, because `IO` is `internal` outside kotlinx-coroutines' JVM source set
 * (checked on 1.10.1 and on 1.11.0, in both `commonMain` and `iosMain`). Callers on the JVM,
 * where `IO` is public, can pass it explicitly. Revisit if coroutines promotes `IO` to common.
 */
fun createPocketNodeCoreDatabase(
    builder: RoomDatabase.Builder<PocketNodeCoreDatabase>,
    driver: SQLiteDriver,
    queryContext: CoroutineContext = Dispatchers.Default,
): PocketNodeCoreDatabase =
    builder
        .setDriver(driver)
        .setQueryCoroutineContext(queryContext)
        .build()
