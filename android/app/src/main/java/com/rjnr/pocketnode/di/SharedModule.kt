package com.rjnr.pocketnode.di

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.validation.NetworkValidator
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
}
