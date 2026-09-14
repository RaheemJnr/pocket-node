package com.rjnr.pocketnode.di

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.AppStatePreferences
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.util.AndroidLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
