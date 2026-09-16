package com.rjnr.pocketnode.di

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.AppStatePreferences
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.data.gateway.AndroidLightClientApi
import com.rjnr.pocketnode.data.gateway.JniLightClient
import com.rjnr.pocketnode.data.gateway.LightClientApi
import com.rjnr.pocketnode.data.gateway.NativeLightClient
import com.rjnr.pocketnode.data.sync.SyncEngine
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
