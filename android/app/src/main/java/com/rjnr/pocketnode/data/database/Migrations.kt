package com.rjnr.pocketnode.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: Add header_cache and dao_cells tables (Phase 2 of Issue #40).
 * Purely additive — existing transactions and balance_cache tables are untouched.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `header_cache` (
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
            """
            CREATE TABLE IF NOT EXISTS `dao_cells` (
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
                PRIMARY KEY(`txHash`, `index`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v2 -> v3: Add wallets table and walletId column to existing tables for multi-wallet support (M3).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create wallets table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wallets` (
                `walletId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `derivationPath` TEXT,
                `parentWalletId` TEXT,
                `accountIndex` INTEGER NOT NULL DEFAULT 0,
                `mainnetAddress` TEXT NOT NULL DEFAULT '',
                `testnetAddress` TEXT NOT NULL DEFAULT '',
                `isActive` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`)
            )
            """.trimIndent()
        )

        // 2. Add walletId column to transactions
        db.execSQL(
            "ALTER TABLE `transactions` ADD COLUMN `walletId` TEXT NOT NULL DEFAULT ''"
        )
        // Create the index Room expects
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_tx_wallet_network_time` ON `transactions` (`walletId`, `network`, `timestamp` DESC)"
        )

        // 3. Recreate balance_cache with composite PK (walletId, network)
        // SQLite can't alter primary keys, so we must recreate the table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `balance_cache_new` (
                `walletId` TEXT NOT NULL DEFAULT '',
                `network` TEXT NOT NULL,
                `address` TEXT NOT NULL DEFAULT '',
                `capacity` TEXT NOT NULL DEFAULT '0',
                `capacityCkb` TEXT NOT NULL DEFAULT '0',
                `blockNumber` TEXT NOT NULL DEFAULT '0',
                `cachedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO `balance_cache_new` SELECT '', `network`, `address`, `capacity`, `capacityCkb`, `blockNumber`, `cachedAt` FROM `balance_cache`")
        db.execSQL("DROP TABLE `balance_cache`")
        db.execSQL("ALTER TABLE `balance_cache_new` RENAME TO `balance_cache`")

        // 4. Add walletId column to dao_cells
        db.execSQL(
            "ALTER TABLE `dao_cells` ADD COLUMN `walletId` TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_dao_wallet_network` ON `dao_cells` (`walletId`, `network`)"
        )

        // 5. Add index to header_cache (entity annotation added in M3 but never created in v1→v2)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_header_network_number` ON `header_cache` (`network`, `number`)"
        )
    }
}

/**
 * v3 → v4: Fix balance_cache PK for users who ran the broken v2→v3 migration,
 * and add Phase 2 columns (lastActiveAt, colorIndex) to wallets table.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Fix balance_cache PK for users who ran broken v2→v3
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `balance_cache_new` (
                `walletId` TEXT NOT NULL DEFAULT '',
                `network` TEXT NOT NULL,
                `address` TEXT NOT NULL DEFAULT '',
                `capacity` TEXT NOT NULL DEFAULT '0',
                `capacityCkb` TEXT NOT NULL DEFAULT '0',
                `blockNumber` TEXT NOT NULL DEFAULT '0',
                `cachedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )
        db.execSQL("INSERT OR IGNORE INTO `balance_cache_new` SELECT `walletId`, `network`, `address`, `capacity`, `capacityCkb`, `blockNumber`, `cachedAt` FROM `balance_cache`")
        db.execSQL("DROP TABLE `balance_cache`")
        db.execSQL("ALTER TABLE `balance_cache_new` RENAME TO `balance_cache`")

        // 2. Add Phase 2 columns to wallets
        db.execSQL("ALTER TABLE `wallets` ADD COLUMN `lastActiveAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `wallets` ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v4 -> v5: Add key_material table for encrypted key storage (Phase 2 of key storage redesign).
 * Key material moves from EncryptedSharedPreferences to Room, encrypted with Android Keystore AES key.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `key_material` (
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
    }
}

/**
 * v5 -> v6: Partial index on PENDING transactions to speed up the
 * "pending-first" sort used by ActivityScreen paging
 * (TransactionDao.getTransactionsPaged / getByWalletAndNetwork).
 *
 * The existing composite index covers (walletId, network, timestamp DESC) but
 * the `CASE WHEN status = 'PENDING'` ORDER BY clause forces a full scan when
 * pending rows are sparse; a partial index keyed only on PENDING rows lets
 * SQLite jump straight to them.
 *
 * Defined in raw SQL because Room's `@Index` annotation does not support
 * WHERE clauses. Room schema validation ignores indices it didn't create.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_tx_pending` " +
                "ON `transactions` (`walletId`, `network`, `timestamp` DESC) " +
                "WHERE `status` = 'PENDING'"
        )
    }
}

/**
 * v6 -> v7: Add sync_progress table for per-wallet sync block tracking (#105).
 * Replaces SharedPreferences storage of lastSyncedBlock. The data copy from
 * SharedPrefs to this table happens at app start via WalletMigrationHelper —
 * this migration only creates the empty table.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_progress` (
                `walletId` TEXT NOT NULL,
                `network` TEXT NOT NULL,
                `lightStartBlockNumber` INTEGER NOT NULL,
                `localSavedBlockNumber` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v7 -> v8: Add pending_broadcasts table for the broadcast state machine (#115).
 *
 * Broadcast-state side of the send-reliability fix; the `transactions`
 * table continues to be the user-facing ledger.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_broadcasts` (
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
            "CREATE INDEX IF NOT EXISTS `idx_pb_wallet_net_state` " +
                "ON `pending_broadcasts` (`walletId`, `network`, `state`)"
        )
    }
}

/**
 * v8 -> v9: No-op identity-hash refresh (#141 / v1.5.2).
 *
 * v1.5.1 shipped with TransactionEntity / BalanceCacheEntity / DaoCellEntity
 * declarations that didn't match the post-migration DB shape (missing
 * @Index(idx_tx_pending) + @ColumnInfo(defaultValue) annotations). The hotfix
 * adds those annotations so the entity declarations match what the migrations
 * actually produced.
 *
 * The DB shape on disk is unchanged. But Room computes its stored identity
 * hash from the entity definitions, so any change to those declarations bumps
 * the hash, and Room refuses to open a DB whose stored hash differs from the
 * current one even when the schema is identical. Bumping the version + adding
 * a no-op migration tells Room to refresh its bookkeeping.
 *
 * Anyone who briefly opened the v1.5.1 build before it crashed wrote the
 * old hash to disk; this migration lets v1.5.2 reset it without forcing a
 * data wipe.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Intentionally empty. See KDoc.
    }
}
