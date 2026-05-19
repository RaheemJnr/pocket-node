package com.rjnr.pocketnode.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.database.MIGRATION_5_6
import com.rjnr.pocketnode.data.database.MIGRATION_6_7
import com.rjnr.pocketnode.data.database.MIGRATION_7_8
import com.rjnr.pocketnode.data.database.MIGRATION_8_9
import com.rjnr.pocketnode.data.database.MIGRATION_9_10
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.gateway.BroadcastClient
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.DaoSyncManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.LightClientBroadcastClient
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.gateway.TransactionStatusUpdater
import com.rjnr.pocketnode.data.sync.LifecycleProvider
import com.rjnr.pocketnode.data.sync.ProcessLifecycleProvider
import com.rjnr.pocketnode.data.sync.RepositoryTransactionStatusGateway
import com.rjnr.pocketnode.data.sync.TransactionStatusGateway
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import android.content.SharedPreferences
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBlake2b(): Blake2b = Blake2b()

    @Provides
    @Singleton
    fun provideKeyManager(
        @ApplicationContext context: Context,
        mnemonicManager: MnemonicManager,
        keyBackupManager: KeyBackupManager,
        keyStoreMigrationHelper: KeyStoreMigrationHelper,
        authManager: AuthManager
    ): KeyManager = KeyManager(context, mnemonicManager).also {
        it.keyBackupManager = keyBackupManager
        it.keyStoreMigrationHelper = keyStoreMigrationHelper
        it.authManager = authManager
    }

    @Provides
    @Singleton
    fun provideKeyBackupManager(
        @ApplicationContext context: Context
    ): KeyBackupManager = KeyBackupManager(File(context.filesDir, "key_backups"))

    @Provides
    @Singleton
    fun provideMnemonicManager(): MnemonicManager = MnemonicManager()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(Android) {
        engine {
            connectTimeout = 10_000  // 10 seconds
            socketTimeout = 10_000   // 10 seconds
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    @Provides
    @Singleton
    fun provideAuthManager(
        @ApplicationContext context: Context
    ): AuthManager = AuthManager(context)

    @Provides
    @Singleton
    fun providePinManager(
        @ApplicationContext context: Context,
        blake2b: Blake2b
    ): PinManager = PinManager(context, blake2b)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pocket_node.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .build()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideBalanceCacheDao(db: AppDatabase): BalanceCacheDao = db.balanceCacheDao()

    @Provides
    fun provideHeaderCacheDao(db: AppDatabase): HeaderCacheDao = db.headerCacheDao()

    @Provides
    fun provideDaoCellDao(db: AppDatabase): DaoCellDao = db.daoCellDao()

    @Provides
    fun provideWalletDao(db: AppDatabase): WalletDao = db.walletDao()

    @Provides
    fun provideKeyMaterialDao(db: AppDatabase): KeyMaterialDao = db.keyMaterialDao()

    @Provides
    fun provideSyncProgressDao(db: AppDatabase): SyncProgressDao = db.syncProgressDao()

    @Provides
    @Singleton
    fun providePendingBroadcastDao(db: AppDatabase): PendingBroadcastDao = db.pendingBroadcastDao()

    @Provides
    @Singleton
    fun provideKeystoreEncryptionManager(): KeystoreEncryptionManager = KeystoreEncryptionManager()

    @Provides
    @Singleton
    @Named("migrationPrefs")
    fun provideMigrationPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("key_migration", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun provideKeyStoreMigrationHelper(
        keyMaterialDao: KeyMaterialDao,
        encryptionManager: KeystoreEncryptionManager,
        @Named("migrationPrefs") migrationPrefs: SharedPreferences
    ): KeyStoreMigrationHelper = KeyStoreMigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)

    @Provides
    @Singleton
    fun provideCacheManager(
        transactionDao: TransactionDao,
        balanceCacheDao: BalanceCacheDao
    ): CacheManager = CacheManager(transactionDao, balanceCacheDao)

    @Provides
    @Singleton
    fun provideDaoSyncManager(
        headerCacheDao: HeaderCacheDao,
        daoCellDao: DaoCellDao
    ): DaoSyncManager = DaoSyncManager(headerCacheDao, daoCellDao)

    @Provides
    @Singleton
    fun provideBroadcastClient(impl: LightClientBroadcastClient): BroadcastClient = impl

    @Provides
    @Singleton
    fun provideTipSource(impl: GatewayRepository): TipSource = impl

    @Provides
    @Singleton
    fun provideTransactionStatusUpdater(impl: CacheManager): TransactionStatusUpdater = impl

    @Provides
    @Singleton
    fun provideTransactionStatusGateway(impl: RepositoryTransactionStatusGateway): TransactionStatusGateway = impl

    @Provides
    @Singleton
    fun provideLifecycleProvider(impl: ProcessLifecycleProvider): LifecycleProvider = impl

    @Provides
    @Singleton
    fun provideGatewayRepository(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
        walletPreferences: WalletPreferences,
        json: Json,
        transactionBuilder: TransactionBuilder,
        cacheManager: CacheManager,
        daoSyncManager: DaoSyncManager,
        walletMigrationHelper: WalletMigrationHelper,
        walletDao: WalletDao,
        appDatabase: AppDatabase,
        headerCacheDao: HeaderCacheDao,
        syncProgressDao: SyncProgressDao,
        pendingBroadcastDao: PendingBroadcastDao,
        broadcastClient: BroadcastClient
    ): GatewayRepository = GatewayRepository(context, keyManager, walletPreferences, json, transactionBuilder, cacheManager, daoSyncManager, walletMigrationHelper, walletDao, appDatabase, headerCacheDao, syncProgressDao, pendingBroadcastDao, broadcastClient)
}
