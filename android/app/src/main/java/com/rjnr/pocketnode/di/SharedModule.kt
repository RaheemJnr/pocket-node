package com.rjnr.pocketnode.di

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.AppStatePreferences
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.data.gateway.AndroidLightClientApi
import com.rjnr.pocketnode.data.gateway.JniLightClient
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientApi
import com.rjnr.pocketnode.data.gateway.NativeLightClient
import com.rjnr.pocketnode.data.gateway.StartupReconciler
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.send.SendPipeline
import com.rjnr.pocketnode.data.storage.BalanceCache
import com.rjnr.pocketnode.data.storage.HeaderCache
import com.rjnr.pocketnode.data.storage.PendingBroadcastStore
import com.rjnr.pocketnode.data.storage.RoomBalanceCache
import com.rjnr.pocketnode.data.storage.RoomHeaderCache
import com.rjnr.pocketnode.data.storage.RoomPendingBroadcastStore
import com.rjnr.pocketnode.data.storage.RoomSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.RoomSyncProgressStore
import com.rjnr.pocketnode.data.storage.RoomTransactionStore
import com.rjnr.pocketnode.data.storage.RoomWalletRegistry
import com.rjnr.pocketnode.data.storage.SubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.SyncProgressStore
import com.rjnr.pocketnode.data.storage.TransactionStore
import com.rjnr.pocketnode.data.storage.WalletRegistry
import com.rjnr.pocketnode.data.sync.BroadcastWatchdog
import com.rjnr.pocketnode.data.sync.LifecycleProvider
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.sync.TransactionStatusGateway
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.util.AndroidLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/**
 * Providers for classes that live in the shared KMP module (D0 in docs/IOS_M1_DESIGN.md):
 * shared classes carry no Hilt annotations; this module wires them for Android.
 */
@Module
@InstallIn(SingletonComponent::class)
object SharedModule {

    @Provides
    @Singleton
    fun provideLogger(): Logger = AndroidLogger()

    @Provides
    @Singleton
    fun provideNetworkValidator(): NetworkValidator = NetworkValidator()

    @Provides
    @Singleton
    fun provideTransactionBuilder(
        networkValidator: NetworkValidator,
        logger: Logger,
    ): TransactionBuilder = TransactionBuilder(networkValidator, logger)

    /**
     * The shared light-client seam. `AndroidLightClientApi` is a pure
     * passthrough to the JNI object, so injecting this changes nothing about
     * how the app talks to the node; it just lets shared code do the same.
     */
    @Provides
    @Singleton
    fun provideLightClientApi(impl: AndroidLightClientApi): LightClientApi = impl

    /** The raw JNI surface behind that seam; see [NativeLightClient]. */
    @Provides
    @Singleton
    fun provideNativeLightClient(impl: JniLightClient): NativeLightClient = impl

    /**
     * The shared sync engine (M3 #2): the poll loop, the progress tracker and
     * the account-status derivation, all in `commonMain`. It constructs its own
     * [com.rjnr.pocketnode.data.gateway.SyncPoller], so nothing else provides
     * one. The clock is left at its `SystemClock` default; only tests inject.
     *
     * `queryContext` is passed explicitly because the engine cannot name
     * `Dispatchers.IO` from `commonMain`. Without it the blocking JNI reads in
     * `readChainSyncState` would land on `Dispatchers.Default`, where they
     * would occupy a CPU-sized pool; every caller on this side (the repository
     * scope and `SyncCatchUpWorker`) ran them on IO before the extraction.
     */
    @Provides
    @Singleton
    fun provideSyncEngine(
        lightClient: LightClientApi,
        syncPreferences: SyncPreferences,
        json: Json,
        logger: Logger,
    ): SyncEngine = SyncEngine(
        lightClient,
        syncPreferences,
        json,
        logger,
        queryContext = Dispatchers.IO,
    )

    // --- Storage seams (M3 #3) ---
    // Four narrow interfaces over the app's Room DAOs, so the shared sync code
    // can read and write the same tables without a Room dependency of its own.
    // iOS binds them in a later M3 issue; nothing in AppContainer.swift
    // implements them yet.

    @Provides
    @Singleton
    fun provideSyncProgressStore(impl: RoomSyncProgressStore): SyncProgressStore = impl

    @Provides
    @Singleton
    fun provideWalletRegistry(impl: RoomWalletRegistry): WalletRegistry = impl

    @Provides
    @Singleton
    fun provideSubAccountCandidateStore(
        impl: RoomSubAccountCandidateStore,
    ): SubAccountCandidateStore = impl

    @Provides
    @Singleton
    fun provideTransactionStore(impl: RoomTransactionStore): TransactionStore = impl

    // --- Storage seams (M3 #4) ---
    // Three more, for the balance cache, the header cache and the broadcast
    // state the read path and the cold-start reconcile consult.

    @Provides
    @Singleton
    fun provideBalanceCache(impl: RoomBalanceCache): BalanceCache = impl

    @Provides
    @Singleton
    fun provideHeaderCache(impl: RoomHeaderCache): HeaderCache = impl

    @Provides
    @Singleton
    fun providePendingBroadcastStore(
        impl: RoomPendingBroadcastStore,
    ): PendingBroadcastStore = impl

    /**
     * The shared read path (M3 #4): balance, live cells, history and the
     * status of one transaction, all in `commonMain` over the seams above.
     *
     * `queryContext` is passed explicitly for the same reason
     * [provideSyncEngine] passes it: `commonMain` cannot name `Dispatchers.IO`,
     * and the one read that hops dispatchers here (the readiness probe) went
     * through `LightClientReadOnly`, which forces IO.
     */
    @Provides
    @Singleton
    fun provideLedgerReader(
        lightClient: LightClientApi,
        balanceCache: BalanceCache,
        transactionStore: TransactionStore,
        headerCache: HeaderCache,
        walletRegistry: WalletRegistry,
        candidates: SubAccountCandidateStore,
        syncPreferences: SyncPreferences,
        uiPreferences: UiPreferences,
        json: Json,
        logger: Logger,
    ): LedgerReader = LedgerReader(
        lightClient,
        balanceCache,
        transactionStore,
        headerCache,
        walletRegistry,
        candidates,
        syncPreferences,
        uiPreferences,
        json,
        logger,
        queryContext = Dispatchers.IO,
    )

    /**
     * The shared cold-start reconcile (M3 #4). It used to carry `@Singleton`
     * and `@Inject` itself; in `commonMain` it carries neither, so the binding
     * moves here.
     */
    @Provides
    @Singleton
    fun provideStartupReconciler(
        pendingBroadcasts: PendingBroadcastStore,
        transactions: TransactionStore,
        logger: Logger,
    ): StartupReconciler = StartupReconciler(pendingBroadcasts, transactions, logger)

    /**
     * The shared send path (M3 #5): preview, build, reserve, broadcast, retry.
     *
     * It owns the send mutex and the session-broadcast set, so there must be
     * exactly one of these per process — a second instance would reintroduce
     * the concurrent-selection race the mutex exists to close.
     *
     * `queryContext` is passed explicitly for the same reason
     * [provideSyncEngine] passes it: the one dispatcher hop in the pipeline is
     * the tip read, which went through `LightClientReadOnly` and forced IO.
     */
    @Provides
    @Singleton
    fun provideSendPipeline(
        lightClient: LightClientApi,
        transactionBuilder: TransactionBuilder,
        ledger: LedgerReader,
        pendingBroadcasts: PendingBroadcastStore,
        transactions: TransactionStore,
        syncEngine: SyncEngine,
        syncCoordinator: SyncCoordinator,
        uiPreferences: UiPreferences,
        json: Json,
        logger: Logger,
    ): SendPipeline = SendPipeline(
        lightClient,
        transactionBuilder,
        ledger,
        pendingBroadcasts,
        transactions,
        syncEngine,
        syncCoordinator,
        uiPreferences,
        json,
        logger,
        queryContext = Dispatchers.IO,
    )

    /**
     * The shared broadcast watchdog (M3 #5). It used to carry `@Singleton` and
     * a second `@Inject` constructor that filled in `Dispatchers.IO`; in
     * `commonMain` it carries neither, so both move here.
     */
    @Provides
    @Singleton
    fun provideBroadcastWatchdog(
        pendingBroadcasts: PendingBroadcastStore,
        statusGateway: TransactionStatusGateway,
        transactions: TransactionStore,
        tipSource: TipSource,
        lifecycleProvider: LifecycleProvider,
        logger: Logger,
    ): BroadcastWatchdog = BroadcastWatchdog(
        pendingBroadcasts,
        statusGateway,
        transactions,
        tipSource,
        lifecycleProvider,
        Dispatchers.IO,
        logger,
    )

    /**
     * The shared multi-wallet script registration (M3 #3): the BALANCED
     * filter, the per-wallet start blocks and the gap-limit candidate
     * registration, all in `commonMain` over the four storage seams above.
     *
     * `queryContext` is passed explicitly for the same reason [provideSyncEngine]
     * passes it: `commonMain` cannot name `Dispatchers.IO`, and every blocking
     * light-client call this coordinator makes ran on IO before the extraction.
     */
    @Provides
    @Singleton
    fun provideSyncCoordinator(
        walletRegistry: WalletRegistry,
        syncProgressStore: SyncProgressStore,
        subAccountCandidateStore: SubAccountCandidateStore,
        transactionStore: TransactionStore,
        lightClient: LightClientApi,
        syncPreferences: SyncPreferences,
        json: Json,
        logger: Logger,
    ): SyncCoordinator = SyncCoordinator(
        walletRegistry,
        syncProgressStore,
        subAccountCandidateStore,
        transactionStore,
        lightClient,
        syncPreferences,
        json,
        logger,
        queryContext = Dispatchers.IO,
    )

    // --- Preference domains (#461) ---
    // One SharedPreferences-backed object behind four narrow interfaces, so a
    // consumer depends on the settings it actually touches. iOS binds the same
    // four interfaces to a UserDefaults implementation in AppContainer.swift.

    @Provides
    @Singleton
    fun provideSyncPreferences(prefs: WalletPreferences): SyncPreferences = prefs

    @Provides
    @Singleton
    fun provideUiPreferences(prefs: WalletPreferences): UiPreferences = prefs

    @Provides
    @Singleton
    fun provideNetworkPreferences(prefs: WalletPreferences): NetworkPreferences = prefs

    @Provides
    @Singleton
    fun provideAppStatePreferences(prefs: WalletPreferences): AppStatePreferences = prefs
}
