package com.rjnr.pocketnode.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background-sync catch-up for the Play build, which ships no foreground service
 * (see BG_FGS_ENABLED, #338/#427). Scheduled by [SyncWorkScheduler] as a ~4h
 * periodic backstop and as a one-time run when the app goes to the background.
 *
 * The worker drives the native light client's progress poller and waits, within
 * a bound that stays well inside WorkManager's ~10-minute execution window, for
 * the client to reach the chain tip. It is best-effort: whatever the light
 * client syncs is persisted, and the next run continues from there.
 *
 * ## Scope note (warm vs cold process)
 *
 * This body reuses only the safe, non-native-lifecycle methods
 * ([GatewayRepository.initializeWallet] derives address state; startSyncPolling
 * polls status). It fully catches up when the process is **warm** (the native
 * node is already running, e.g. the one-time run right after the app is
 * backgrounded, which is the main freshness win). In a **cold** process the
 * node is not running; the probe below detects "no tip within PROBE_MS" and
 * returns instead of spinning. Cold-start of the native node from a worker
 * touches the OnceLock lifecycle (the same reason a network switch restarts the
 * process) and is intentionally left as a device-tested follow-up rather than
 * coded blind. See #337.
 */
@HiltWorker
class SyncCatchUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: GatewayRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Builds that keep the foreground service never rely on WorkManager for
        // sync. The scheduler already guards this; this is defence in depth so a
        // stale enqueued request on an upgraded install is a no-op.
        if (BuildConfig.BG_FGS_ENABLED) return Result.success()

        if (!repository.hasWallet()) {
            Log.d(TAG, "No wallet; nothing to catch up")
            return Result.success()
        }

        return try {
            repository.initializeWallet()

            val outcome = withTimeoutOrNull(MAX_RUN_MS) {
                // Poll getAccountStatus() directly rather than driving the shared
                // startSyncPolling job. That job is process-wide state owned by
                // the foreground UI; a worker that started/stopped it would cancel
                // the Home screen's progress poller on a warm process and stale it
                // (Codex #428 P1). getAccountStatus is a cheap local JNI read with
                // no shared ownership. The native client keeps syncing on its own
                // threads while WorkManager holds the process alive here; we only
                // observe progress and return once caught up.
                var sawNode = false
                val probeDeadline = System.currentTimeMillis() + PROBE_MS
                var result: String? = null
                while (result == null) {
                    val status = repository.getAccountStatus().getOrNull()
                    val synced = status?.syncedToBlock?.toLongOrNull() ?: 0L
                    val tip = status?.tipNumber?.toLongOrNull() ?: 0L
                    when {
                        tip > 0L && synced >= tip -> result = "caught-up"
                        tip > 0L -> sawNode = true
                    }
                    // No tip within PROBE_MS => native client not running in this
                    // (likely cold) process. Bail rather than spin the full window.
                    if (result == null && !sawNode &&
                        System.currentTimeMillis() > probeDeadline
                    ) {
                        result = "no-live-node"
                    }
                    if (result == null) delay(POLL_INTERVAL_MS)
                }
                result
            } ?: "timed-out-still-syncing"

            Log.i(TAG, "catch-up finished: $outcome")
            Result.success() // best-effort in every case; next run continues
        } catch (e: Exception) {
            Log.w(TAG, "catch-up failed; will retry with backoff", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncCatchUpWorker"

        // Stay comfortably inside WorkManager's ~10-minute cap.
        private const val MAX_RUN_MS = 8L * 60L * 1000L

        // How long to wait for the native client to show any chain data before
        // concluding it is not running in this process.
        private const val PROBE_MS = 45L * 1000L

        // Cadence for the direct getAccountStatus() catch-up poll.
        private const val POLL_INTERVAL_MS = 5L * 1000L
    }
}
