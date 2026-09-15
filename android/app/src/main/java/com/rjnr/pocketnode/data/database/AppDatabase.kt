package com.rjnr.pocketnode.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.ContactDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.dao.PendingDaoWithdrawDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import com.rjnr.pocketnode.data.database.entity.PendingDaoWithdrawEntity
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity

@Database(
    entities = [
        TransactionEntity::class,
        BalanceCacheEntity::class,
        HeaderCacheEntity::class,
        DaoCellEntity::class,
        WalletEntity::class,
        KeyMaterialEntity::class,
        SyncProgressEntity::class,
        PendingBroadcastEntity::class,
        ContactEntity::class,
        PendingDaoWithdrawEntity::class,
        SubAccountCandidateEntity::class,
    ],
    // Bumped from 8 to 9 in v1.5.2 because TransactionEntity / BalanceCacheEntity
    // / DaoCellEntity gained @Index(idx_tx_pending) + @ColumnInfo(defaultValue)
    // annotations. The DB shape is unchanged (the migrations 1→8 already
    // produced this exact shape; the annotations just declare it correctly),
    // so MIGRATION_8_9 is a no-op. Version bump alone is needed to refresh
    // Room's stored identity hash after the entity declarations changed. (#90 / #141)
    //
    // Bumped from 9 to 10 for v1.7.0 because KeyMaterialEntity gained the
    // `kdfVersion` column (default 1). Existing rows are tagged version 1
    // (legacy unrestricted V1 keystore key); the v1.6.x → v1.7.0 migration
    // re-encrypts them under the auth-bound V2 keystore key and bumps each
    // row to version 2. See `KeystoreV2MigrationHelper` and #213.
    //
    // Bumped from 10 to 11 for the M4 Phase 2 address book (#189). Adds
    // the `contacts` table with indices on address and walletId. No
    // existing column changed; MIGRATION_10_11 is a single CREATE TABLE
    // + 2 CREATE INDEX.
    //
    // Bumped from 11 to 12 for #347: adds the `pending_dao_withdraws` table —
    // durable marker for an in-flight DAO phase-1 withdraw. MIGRATION_11_12 is
    // a single CREATE TABLE + 1 CREATE INDEX.
    //
    // Bumped from 12 to 13 for #82 phase 1: adds `sub_account_candidates`
    // (HD discovery slots recorded at parent import). MIGRATION_12_13 is a
    // single CREATE TABLE.
    //
    // Bumped from 13 to 14: sub_account_candidates gains registeredFromBlock
    // (#82 coverage gate). MIGRATION_13_14 is a single ALTER TABLE.
    //
    // Bumped from 14 to 15 for #382 Tier 2: sub_account_candidates re-keyed
    // on (parentWalletId, derivationPath) so chain-axis gap-limit slots can
    // coexist with account-axis slots. MIGRATION_14_15 recreates the table
    // and backfills derivationPath from accountIndex.
    version = 15,
    // Schema export re-enabled (#149). It was OFF because Room 2.8.4's
    // bundled kotlinx-serialization-core crashed KSP with an AbstractMethodError
    // against the project's kotlinx-serialization-json:1.8.0. Verified clean
    // on Kotlin 2.3.10 / KSP 2.3.10 / Room 2.8.4 — JSON schemas land under
    // app/schemas/, reviewable in PR diffs alongside migrations.
    //
    // The walking-migration test under src/test/ still does the runtime
    // schema-validation work (#142's regression-guard); this restores the
    // JSON diff in PR review on top of that.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun balanceCacheDao(): BalanceCacheDao
    abstract fun headerCacheDao(): HeaderCacheDao
    abstract fun daoCellDao(): DaoCellDao
    abstract fun walletDao(): WalletDao
    abstract fun keyMaterialDao(): KeyMaterialDao
    abstract fun syncProgressDao(): SyncProgressDao
    abstract fun pendingBroadcastDao(): PendingBroadcastDao
    abstract fun contactDao(): ContactDao
    abstract fun pendingDaoWithdrawDao(): PendingDaoWithdrawDao
    abstract fun subAccountCandidateDao(): com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
}
