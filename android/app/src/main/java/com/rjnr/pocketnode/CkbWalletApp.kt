package com.rjnr.pocketnode

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.rjnr.pocketnode.data.sync.BroadcastWatchdog
import com.rjnr.pocketnode.data.sync.SyncWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CkbWalletApp : Application(), Configuration.Provider {
    @Inject lateinit var broadcastWatchdog: BroadcastWatchdog

    // Supplies the HiltWorkerFactory so @HiltWorker workers can be constructed
    // with their injected dependencies. Paired with the default WorkManager
    // initializer being removed in the manifest (on-demand initialization).
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncWorkScheduler: SyncWorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        broadcastWatchdog.start()
        // Register the periodic background-sync catch-up. No-ops on builds that
        // keep the foreground service (BG_FGS_ENABLED=true); only the Play build
        // relies on WorkManager for background freshness (#337).
        syncWorkScheduler.ensurePeriodicCatchUpScheduled()
    }
}
