package com.rjnr.pocketnode.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
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
        PendingBroadcastEntity::class
    ],
    // Bumped from 8 to 9 in v1.5.2 because TransactionEntity / BalanceCacheEntity
    // / DaoCellEntity gained @Index(idx_tx_pending) + @ColumnInfo(defaultValue)
    // annotations. The DB shape is unchanged (the migrations 1→8 already
    // produced this exact shape; the annotations just declare it correctly),
    // so MIGRATION_8_9 is a no-op. Version bump alone is needed to refresh
    // Room's stored identity hash after the entity declarations changed. (#90 / #141)
    version = 9,
    exportSchema = false
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
}
