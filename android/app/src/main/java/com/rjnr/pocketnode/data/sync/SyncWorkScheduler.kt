package com.rjnr.pocketnode.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rjnr.pocketnode.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the [SyncCatchUpWorker] for the Play build (Option D, #337):
 *
 *  - a **~4h periodic** backstop, and
 *  - a **one-time** run enqueued when the app goes to the background, which
 *    catches up while the process is still warm (the freshness win).
 *
 * All scheduling is gated on `!BG_FGS_ENABLED`: the GitHub/website build keeps
 * its foreground service and must not also run WorkManager sync, so there is
 * exactly one background mechanism per build. On a build that has the FGS, the
 * methods here cancel any previously scheduled work (covers a user moving from a
 * Play install to a sideloaded build) and otherwise no-op.
 */
@Singleton
class SyncWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        // Any network (incremental light-client sync is small). A "sync on
        // metered" user preference can tighten this later (#338).
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Registered once at app start (CkbWalletApp). Idempotent. */
    fun ensurePeriodicCatchUpScheduled() {
        if (BuildConfig.BG_FGS_ENABLED) {
            // FGS build: make sure no stale periodic work lingers.
            workManager.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncCatchUpWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
            PERIODIC_FLEX_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        // KEEP so app restarts don't reset the interval timer.
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Periodic catch-up scheduled (${PERIODIC_INTERVAL_HOURS}h)")
    }

    /** Enqueued when the app goes to the background (MainActivity.onStop). */
    fun enqueueBackgroundCatchUp() {
        if (BuildConfig.BG_FGS_ENABLED) return
        val request = OneTimeWorkRequestBuilder<SyncCatchUpWorker>()
            .setConstraints(constraints)
            .build()

        // KEEP so rapid background/foreground toggling doesn't restart a run
        // that is already queued or executing.
        workManager.enqueueUniqueWork(
            ONESHOT_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "One-time background catch-up enqueued")
    }

    companion object {
        private const val TAG = "SyncWorkScheduler"
        private const val PERIODIC_NAME = "sync-catch-up-periodic"
        private const val ONESHOT_NAME = "sync-catch-up-oneshot"
        private const val PERIODIC_INTERVAL_HOURS = 4L
        private const val PERIODIC_FLEX_HOURS = 1L
    }
}
