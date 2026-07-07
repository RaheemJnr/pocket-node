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
import com.rjnr.pocketnode.data.database.MIGRATION_10_11
import com.rjnr.pocketnode.data.database.MIGRATION_11_12
import com.rjnr.pocketnode.data.database.MIGRATION_12_13
import com.rjnr.pocketnode.data.database.MIGRATION_13_14
import com.rjnr.pocketnode.data.database.MIGRATION_14_15
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.ContactDao
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
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
    @Singleton
    fun providePendingDaoWithdrawDao(db: AppDatabase): com.rjnr.pocketnode.data.database.dao.PendingDaoWithdrawDao =
        db.pendingDaoWithdrawDao()

    @Provides
    fun provideWalletDao(db: AppDatabase): WalletDao = db.walletDao()

    @Provides
    fun provideSubAccountCandidateDao(db: AppDatabase): com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao =
        db.subAccountCandidateDao()

    @Provides
    fun provideKeyMaterialDao(db: AppDatabase): KeyMaterialDao = db.keyMaterialDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

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
    fun provideLightClientBridge(
        impl: com.rjnr.pocketnode.data.gateway.LightClientNativeBridge,
    ): com.rjnr.pocketnode.data.gateway.LightClientBridge = impl

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
    fun provideKeystoreV2MigrationHelper(
        keyMaterialDao: KeyMaterialDao,
        encryptionManager: KeystoreEncryptionManager,
        @Named("migrationPrefs") migrationPrefs: SharedPreferences
    ): com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper =
        com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper(
            keyMaterialDao, encryptionManager, migrationPrefs
        )

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
        daoCellDao: DaoCellDao,
        pendingDaoWithdrawDao: com.rjnr.pocketnode.data.database.dao.PendingDaoWithdrawDao,
    ): DaoSyncManager = DaoSyncManager(headerCacheDao, daoCellDao, pendingDaoWithdrawDao)

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
        broadcastClient: BroadcastClient,
        syncCoordinator: com.rjnr.pocketnode.data.gateway.SyncCoordinator,
        daoHeaderResolver: com.rjnr.pocketnode.data.gateway.DaoHeaderResolver,
        daoDepositReader: com.rjnr.pocketnode.data.gateway.DaoDepositReader,
        lightClient: com.rjnr.pocketnode.data.gateway.LightClientReadOnly,
        subAccountReconciler: com.rjnr.pocketnode.data.wallet.SubAccountReconciler,
    ): GatewayRepository = GatewayRepository(context, keyManager, walletPreferences, json, transactionBuilder, cacheManager, daoSyncManager, walletMigrationHelper, walletDao, appDatabase, headerCacheDao, syncProgressDao, pendingBroadcastDao, broadcastClient, syncCoordinator, daoHeaderResolver, daoDepositReader, lightClient, subAccountReconciler)

    /**
     * Production activity probe for sub-account discovery (#82 phase 2):
     * one nativeGetTransactions(limit=1) against the candidate's lock
     * script. Null (indeterminate) when the light client returns null —
     * the reconciler retries on a later pass instead of mis-classifying.
     */
    @Provides
    @Singleton
    fun provideSubAccountActivityProbe(json: Json): com.rjnr.pocketnode.data.wallet.SubAccountActivityProbe =
        com.rjnr.pocketnode.data.wallet.SubAccountActivityProbe { scriptArgs ->
            runCatching {
                val searchKey = com.rjnr.pocketnode.data.gateway.models.JniSearchKey(
                    script = com.rjnr.pocketnode.data.gateway.models.Script(
                        codeHash = com.rjnr.pocketnode.data.gateway.models.Script.SECP256K1_CODE_HASH,
                        hashType = "type",
                        args = scriptArgs,
                    )
                )
                val raw = com.nervosnetwork.ckblightclient.LightClientNative.nativeGetTransactions(
                    json.encodeToString(
                        com.rjnr.pocketnode.data.gateway.models.JniSearchKey.serializer(),
                        searchKey,
                    ),
                    "desc",
                    1,
                    null,
                ) ?: return@runCatching null
                val page = json.decodeFromString<
                    com.rjnr.pocketnode.data.gateway.models.JniPagination<
                        com.rjnr.pocketnode.data.gateway.models.JniTxWithCell>>(raw)
                page.objects.isNotEmpty()
            }.getOrNull()
        }
}
