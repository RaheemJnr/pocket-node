package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.room.migration.Migration
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Walking-migration regression guard for the v1.5.1 / v1.5.2 hotfix shapes (#141, #143).
 *
 * The existing [MigrationTest] uses [Room.inMemoryDatabaseBuilder] which
 * builds the schema directly from the current entity declarations and
 * never exercises [Migration.migrate] code paths. So when the v1.5.1
 * entity declarations drifted from what the migrations produced on disk,
 * the in-memory test passed but real upgraders crashed at launch.
 *
 * This test class drives Room down the actual upgrade path: it creates a
 * SQLite file with a known historical shape, then opens it via
 * [Room.databaseBuilder] with the migrations registered. Room runs
 * `onUpgrade` (which executes the migrations) and then validates the
 * resulting schema against the entity declarations. If any drift exists
 * between migration output and entity declarations, validation throws
 * [IllegalStateException] and the test fails.
 *
 * Two divergent v8 shapes existed in the wild after v1.5.1:
 *
 *   - **Path A (upgrade from v1.5.0):** ran [MIGRATION_2_3] (which adds
 *     walletId with `DEFAULT ''`) and [MIGRATION_5_6] (which creates a
 *     partial `idx_tx_pending`). Both present.
 *   - **Path B (fresh install on v1.5.1):** Room created v8 directly
 *     from v1.5.1's broken entity declarations. Neither present.
 *
 * Both paths must converge to v9 successfully. The dedicated tests below
 * cover each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalkingMigrationTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "walking-migration-test.db"
    private var openedRoomDb: AppDatabase? = null

    @After
    fun teardown() {
        openedRoomDb?.close()
        openedRoomDb = null
        ctx.deleteDatabase(dbName)
    }

    /**
     * v1.5.0 upgrader: migrations 1→8 produced this exact shape.
     * - `transactions.walletId TEXT NOT NULL DEFAULT ''`
     * - `idx_tx_pending` exists (partial, with `WHERE status = 'PENDING'`)
     * - `balance_cache` and `dao_cells` walletId also `DEFAULT ''`
     * MIGRATION_8_9 should be idempotent here.
     */
    @Test
    fun `path A v8 walks to v9 successfully`() {
        bootstrapV8(pathA = true)
        openViaRoomAndValidate()
    }

    /**
     * v1.5.1 fresh installer: Room created v8 schema directly from
     * v1.5.1's entity declarations, which lacked both the partial index
     * and the `DEFAULT ''` annotations. MIGRATION_8_9 must add the
     * missing pieces.
     */
    @Test
    fun `path B v8 walks to v9 successfully`() {
        bootstrapV8(pathA = false)
        openViaRoomAndValidate()
    }

    /**
     * v10 → v11 path (#189 address book): bootstrap a v10 SQLite file
     * and confirm MIGRATION_10_11 creates the contacts table with both
     * indices, after which schema validation passes.
     */
    @Test
    fun `v10 walks to v11 successfully and creates contacts table`() {
        bootstrapV10()
        openViaRoomAndValidate()
        val db = openedRoomDb!!.openHelper.readableDatabase
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='contacts'").use { c ->
            assertTrue("contacts table missing after MIGRATION_10_11", c.moveToNext())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_contacts_address'").use { c ->
            assertTrue("idx_contacts_address missing", c.moveToNext())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_contacts_walletId'").use { c ->
            assertTrue("idx_contacts_walletId missing", c.moveToNext())
        }
    }

    /**
     * #347: a v10 file walked all the way to v12 must gain the
     * `pending_dao_withdraws` table + its index (MIGRATION_11_12), with
     * schema validation passing.
     */
    @Test
    fun `walk to v12 creates pending_dao_withdraws table`() {
        bootstrapV10()
        openViaRoomAndValidate()
        val db = openedRoomDb!!.openHelper.readableDatabase
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='pending_dao_withdraws'").use { c ->
            assertTrue("pending_dao_withdraws table missing after MIGRATION_11_12", c.moveToNext())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_pending_withdraw_wallet_network'").use { c ->
            assertTrue("idx_pending_withdraw_wallet_network missing", c.moveToNext())
        }
    }

    /**
     * #82 phase 1: a v10 file walked to v13 must gain the
     * `sub_account_candidates` table (MIGRATION_12_13), with schema
     * validation passing.
     */
    @Test
    fun `walk to v13 creates sub_account_candidates table`() {
        bootstrapV10()
        openViaRoomAndValidate()
        val db = openedRoomDb!!.openHelper.readableDatabase
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sub_account_candidates'").use { c ->
            assertTrue("sub_account_candidates table missing after MIGRATION_12_13", c.moveToNext())
        }
    }

    /**
     * v1.6.x → v1.7.0 path: bootstrap a v9 SQLite file (no `kdfVersion`
     * column on `key_material`) and confirm MIGRATION_9_10 adds the
     * column with default 1, then Room schema validation passes.
     */
    @Test
    fun `v9 walks to v10 successfully and adds kdfVersion column`() {
        bootstrapV9()
        openViaRoomAndValidate()
        // Re-open and inspect: kdfVersion column exists with DEFAULT 1.
        val db = openedRoomDb!!.openHelper.readableDatabase
        db.query("PRAGMA table_info(`key_material`)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            assertTrue("kdfVersion column missing after MIGRATION_9_10: $names", names.contains("kdfVersion"))
        }
    }

    /**
     * Regression guard for the v1.5.1 → v1.5.2 hotfix (#143).
     *
     * If `MIGRATION_8_9` is reverted to a no-op (which is what the first
     * cut of the hotfix shipped — sufficient for path A but not path B),
     * a fresh-install-on-v1.5.1 user crashes on launch. This test
     * confirms the failure surfaces as an `IllegalStateException` from
     * Room's schema validation. If this test ever stops failing under a
     * no-op migration, it means schema validation is no longer catching
     * the drift, and we have lost our regression guard.
     */
    @Test
    fun `path B fails loudly when MIGRATION_8_9 is a no-op`() {
        val noOpMigration8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op — what the first cut of #143 shipped. Path A users
                // were fine because their on-disk shape already matched the
                // entity declarations, but path B users had neither
                // idx_tx_pending nor walletId DEFAULT '' and crashed.
            }
        }
        bootstrapV8(pathA = false)
        try {
            val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, noOpMigration8To9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                )
                .build()
            openedRoomDb = db
            db.openHelper.writableDatabase
            fail("Expected schema validation to fail with a no-op MIGRATION_8_9")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Expected the failure to mention table-info mismatch, got: ${e.message}",
                e.message?.contains("Migration didn't properly handle") == true,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Opens the on-disk DB via Room with all migrations registered. Room
     * runs the migration sequence to bring the stored version up to the
     * current `@Database` version, then validates the schema against the
     * entity declarations. Fetching the writable database forces the
     * `onUpgrade` + validation path.
     */
    private fun openViaRoomAndValidate() {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
            )
            .build()
        openedRoomDb = db
        // Force open. Validation runs eagerly during the first connection,
        // so any TableInfo mismatch surfaces here.
        db.openHelper.writableDatabase
    }

    /**
     * Creates a SQLite file at version 8 with either the post-upgrade
     * shape (path A) or the fresh-install-on-v1.5.1 shape (path B). The
     * helper uses Android's [SupportSQLiteOpenHelper] and reports
     * version 8 so when Room subsequently opens it at version 9,
     * `onUpgrade` fires and runs MIGRATION_8_9.
     */
    /**
     * Bootstrap a v10 SQLite file: applies MIGRATION_8_9 + MIGRATION_9_10
     * over the v8 shape so the on-disk DB matches what a real
     * upgraded-to-v10 user has. The key thing for MIGRATION_10_11
     * coverage is that the `contacts` table does NOT exist yet.
     */
    private fun bootstrapV10() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV8Tables(db, pathA = true)
                MIGRATION_8_9.migrate(db)
                MIGRATION_9_10.migrate(db)
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42, 'bootstrap-v10')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = factory.create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    /**
     * Bootstrap a v9 SQLite file: same as `bootstrapV8(pathA = true)` but
     * also applies the MIGRATION_8_9 changes inline so the on-disk shape
     * matches what a real upgraded-to-v9 user has. The key thing for
     * MIGRATION_9_10 coverage is that `key_material` has no `kdfVersion`
     * column.
     */
    private fun bootstrapV9() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV8Tables(db, pathA = true)
                MIGRATION_8_9.migrate(db)
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42, 'bootstrap-v9')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = factory.create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    private fun bootstrapV8(pathA: Boolean) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val callback = object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV8Tables(db, pathA)
                // Room writes its own bookkeeping table on first open. Mirror
                // that here so Room's identity-hash check on the subsequent
                // open doesn't complain about a missing room_master_table.
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42, 'bootstrap-v8')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = factory.create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    /**
     * Run-out of the v8 schema. `pathA = true` matches what migrations
     * 1→8 produced for an upgrader from v1.5.0. `pathA = false` matches
     * what Room created from v1.5.1's broken entity declarations on a
     * fresh install.
     *
     * Differences between the two:
     * - `transactions.walletId`: DEFAULT '' (path A) vs no default (path B)
     * - `idx_tx_pending`: present partial (path A) vs absent (path B)
     * - `balance_cache.walletId`: DEFAULT '' (path A) vs no default (path B)
     * - `dao_cells.walletId`: DEFAULT '' (path A) vs no default (path B)
     */
    private fun createV8Tables(db: SupportSQLiteDatabase, pathA: Boolean) {
        val walletIdDefault = if (pathA) " DEFAULT ''" else ""

        // transactions
        db.execSQL(
            """
            CREATE TABLE `transactions` (
                `txHash` TEXT NOT NULL,
                `blockNumber` TEXT NOT NULL,
                `blockHash` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `balanceChange` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `fee` TEXT NOT NULL,
                `confirmations` INTEGER NOT NULL,
                `blockTimestampHex` TEXT,
                `network` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `isLocal` INTEGER NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                `walletId` TEXT NOT NULL$walletIdDefault,
                PRIMARY KEY(`txHash`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX `idx_tx_wallet_network_time` " +
                "ON `transactions` (`walletId`, `network`, `timestamp` DESC)"
        )
        if (pathA) {
            // Partial index, exactly as MIGRATION_5_6 created it.
            db.execSQL(
                "CREATE INDEX `idx_tx_pending` " +
                    "ON `transactions` (`walletId`, `network`, `timestamp` DESC) " +
                    "WHERE `status` = 'PENDING'"
            )
        }

        // balance_cache
        db.execSQL(
            """
            CREATE TABLE `balance_cache` (
                `walletId` TEXT NOT NULL$walletIdDefault,
                `network` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `capacity` TEXT NOT NULL,
                `capacityCkb` TEXT NOT NULL,
                `blockNumber` TEXT NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )

        // dao_cells
        db.execSQL(
            """
            CREATE TABLE `dao_cells` (
                `txHash` TEXT NOT NULL,
                `index` TEXT NOT NULL,
                `capacity` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `depositBlockNumber` INTEGER NOT NULL,
                `depositBlockHash` TEXT NOT NULL,
                `depositEpochHex` TEXT,
                `withdrawBlockNumber` INTEGER,
                `withdrawBlockHash` TEXT,
                `withdrawEpochHex` TEXT,
                `compensation` INTEGER NOT NULL,
                `unlockEpochHex` TEXT,
                `depositTimestamp` INTEGER NOT NULL,
                `network` TEXT NOT NULL,
                `lastUpdatedAt` INTEGER NOT NULL,
                `walletId` TEXT NOT NULL$walletIdDefault,
                PRIMARY KEY(`txHash`, `index`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX `idx_dao_wallet_network` " +
                "ON `dao_cells` (`walletId`, `network`)"
        )

        // header_cache. Entity declares PK on blockHash only (not composite)
        // and an index on (network, number).
        db.execSQL(
            """
            CREATE TABLE `header_cache` (
                `blockHash` TEXT NOT NULL,
                `number` TEXT NOT NULL,
                `epoch` TEXT NOT NULL,
                `timestamp` TEXT NOT NULL,
                `dao` TEXT NOT NULL,
                `network` TEXT NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`blockHash`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX `idx_header_network_number` " +
                "ON `header_cache` (`network`, `number`)"
        )

        // wallets (M3, MIGRATION_2_3).
        db.execSQL(
            """
            CREATE TABLE `wallets` (
                `walletId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `derivationPath` TEXT,
                `parentWalletId` TEXT,
                `accountIndex` INTEGER NOT NULL,
                `mainnetAddress` TEXT NOT NULL,
                `testnetAddress` TEXT NOT NULL,
                `isActive` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastActiveAt` INTEGER NOT NULL,
                `colorIndex` INTEGER NOT NULL,
                PRIMARY KEY(`walletId`)
            )
            """.trimIndent()
        )

        // key_material (MIGRATION_4_5).
        db.execSQL(
            """
            CREATE TABLE `key_material` (
                `walletId` TEXT NOT NULL,
                `encryptedPrivateKey` BLOB NOT NULL,
                `encryptedMnemonic` BLOB,
                `iv` BLOB NOT NULL,
                `walletType` TEXT NOT NULL,
                `mnemonicBackedUp` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`)
            )
            """.trimIndent()
        )

        // sync_progress (MIGRATION_6_7).
        db.execSQL(
            """
            CREATE TABLE `sync_progress` (
                `walletId` TEXT NOT NULL,
                `network` TEXT NOT NULL,
                `lightStartBlockNumber` INTEGER NOT NULL,
                `localSavedBlockNumber` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )

        // pending_broadcasts (MIGRATION_7_8).
        db.execSQL(
            """
            CREATE TABLE `pending_broadcasts` (
                `txHash` TEXT NOT NULL,
                `walletId` TEXT NOT NULL,
                `network` TEXT NOT NULL,
                `signedTxJson` TEXT NOT NULL,
                `reservedInputs` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `submittedAtTipBlock` INTEGER NOT NULL,
                `nullCount` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastCheckedAt` INTEGER NOT NULL,
                PRIMARY KEY(`txHash`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX `idx_pb_wallet_net_state` " +
                "ON `pending_broadcasts` (`walletId`, `network`, `state`)"
        )
    }
}
