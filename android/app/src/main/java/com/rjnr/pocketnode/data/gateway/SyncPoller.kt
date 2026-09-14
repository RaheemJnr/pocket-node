package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.gateway.models.AccountStatusResponse
import com.rjnr.pocketnode.data.sync.SyncProgressTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class SyncProgress(
    val isSyncing: Boolean = false,
    val syncedToBlock: Long = 0L,
    val tipBlockNumber: Long = 0L,
    val percentage: Double = 0.0,
    val etaDisplay: String = "",
    val justReachedTip: Boolean = false,
    val firstCatchingUpAtMs: Long? = null
)

/**
 * Edge-trigger first-time tracking for the "catching up" state.
 *
 * - When `catching` flips false→true, returns `nowMs` (start of the run).
 * - While `catching` stays true, returns `prev` unchanged.
 * - When `catching` is false, returns null.
 */
fun computeFirstCatchingUpAtMs(prev: Long?, catching: Boolean, nowMs: Long): Long? = when {
    !catching -> null
    prev == null -> nowMs
    else -> prev
}

/**
 * What the poll loop needs from [GatewayRepository], narrowed to two calls so
 * [SyncPoller] carries no dependency back on the repository. The repository
 * implements this and hands `this` to [SyncPoller.start].
 */
interface SyncPollSource {
    /** True once a wallet is loaded into repository state (decrypted, unlocked). */
    fun hasWalletInfo(): Boolean

    /** Tip + per-script sync state for the active wallet. */
    suspend fun getAccountStatus(): Result<AccountStatusResponse>
}

/**
 * Centralized sync polling extracted from [GatewayRepository] (#460 part 2).
 *
 * Owns the whole "how far along is this wallet" surface:
 *
 *  1. the polling job and its 5s (catching up) / 10s (synced) cadence;
 *  2. the [SyncProgressTracker] samples behind the percentage and ETA,
 *     including the wallet-switch reset/seed hooks;
 *  3. the derived [syncProgress] state — the `justReachedTip` edge, the
 *     `firstCatchingUpAtMs` coachmark grace tracker (#90) and the throttled
 *     `lastSyncedAt` pref write (#286);
 *  4. the monotonic [tipFlow] that `BroadcastWatchdog` wakes on.
 *
 * Coroutine semantics are unchanged from the repository: [start] takes the
 * caller's [CoroutineScope] and launches the loop there, so the loop still
 * runs under the repository's `SupervisorJob + Dispatchers.IO +
 * CoroutineExceptionHandler` scope and dies with it. Nothing here creates a
 * scope of its own.
 *
 * Plain `@Singleton` with no dependency back on [GatewayRepository]; the
 * repository forwards its public sync API here so ViewModels and tests are
 * unchanged.
 */
@Singleton
class SyncPoller @Inject constructor(
    private val syncPreferences: SyncPreferences,
    private val logger: Logger,
) {

    private val _tipFlow = MutableStateFlow(0L)
    val tipFlow: StateFlow<Long> = _tipFlow.asStateFlow()

    /**
     * Publish a fresh tip to [tipFlow]. Monotonic — older tips are ignored
     * (light-client tip events can interleave). Public-by-package so the
     * sync polling path and send path can both keep the flow warm without
     * exposing a setter to outside callers.
     */
    internal fun publishTip(n: Long) {
        if (n > _tipFlow.value) _tipFlow.value = n
    }

    // --- Sync progress tracking ---
    private val syncProgressTracker = SyncProgressTracker()
    private var syncPollingJob: Job? = null

    // Generation token bumped on every start/stop of sync polling. In-flight
    // getAccountStatus().onSuccess lambdas capture the generation at the start
    // of each iteration and refuse to write `firstCatchingUpAtMs` /
    // `_syncProgress` if it's stale — coroutine cancellation is cooperative,
    // so without this gate a successful HTTP response that returned just
    // before stopSyncPolling() could resurrect the cleared state. (#90)
    @Volatile
    private var syncPollingGeneration: Long = 0L
    private var wasSyncing = false
    // Process-lifetime edge-tracker for the first time the wallet entered
    // "catching up" (actively downloading blocks). Used by the HomeViewModel
    // coachmark grace timer (#90). Null whenever we are not catching up.
    private var firstCatchingUpAtMs: Long? = null
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    // Throttle for the lastSyncedAt pref write in the sync poll (#286).
    @Volatile
    private var lastSyncedAtWrittenMs = 0L

    /**
     * Drop the previous wallet's sync samples. Called on a wallet switch
     * before the new wallet's baseline is read.
     */
    fun resetTracker() = syncProgressTracker.reset()

    /** @see SyncProgressTracker.seedStartHeight */
    fun seedStartHeight(startBlock: Long) = syncProgressTracker.seedStartHeight(startBlock)

    /** Clear the derived progress state on a wallet switch. */
    fun resetProgressState() {
        wasSyncing = false
        firstCatchingUpAtMs = null
        _syncProgress.value = SyncProgress()
    }

    /** Percentage/ETA for an arbitrary tip, off the same sample window. */
    fun calculate(tipHeight: Long): SyncProgressTracker.ProgressInfo =
        syncProgressTracker.calculate(tipHeight)

    /**
     * Start centralized sync polling. Idempotent — does nothing if already running.
     * Polls getAccountStatus(), records samples, calculates progress, and emits to syncProgress flow.
     *
     * The loop is launched in [scope] — the repository's, not one owned here —
     * so cancelling that scope stops the poll.
     */
    fun start(scope: CoroutineScope, source: SyncPollSource) {
        if (syncPollingJob?.isActive == true) return

        val generation = ++syncPollingGeneration

        syncPollingJob = scope.launch {
            logger.d(TAG, "Starting centralized sync polling")
            while (true) {
                // Wrap each poll iteration so an exception (JNI panic-returned-
                // null, state mutation race, notification update failure)
                // doesn't kill the polling loop. Without this, one bad cycle
                // produces a permanently-stuck "Syncing..." UI even though
                // the scope's SupervisorJob keeps the process alive. The
                // catch logs and waits for the next cycle.
                try {
                    pollSyncOnce(generation, source)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // honour structured concurrency
                } catch (e: Throwable) {
                    logger.e(TAG, "syncPoll iteration failed; continuing", e)
                }
                // Synced cadence is 10s (was 30s): balance only refreshes off
                // this poll, so a confirmed incoming tx could lag up to ~30s
                // after "Synced" (#355/#356). The poll is cheap local JNI
                // (tip header + account status); 10s bounds the lag without
                // meaningful battery cost. Catch-up stays at 5s.
                val delayMs = if (_syncProgress.value.isSyncing) 5_000L else 10_000L
                delay(delayMs)
            }
        }
    }

    /**
     * Single sync-poll iteration extracted from [start] so the
     * while loop can wrap each call in a try-catch without losing the
     * generation-guard semantics. Any throwable inside this function logs
     * and returns; the loop continues on the next tick.
     */
    private suspend fun pollSyncOnce(generation: Long, source: SyncPollSource) {
        // Skip the iteration when no wallet is loaded into repo state.
        // Happens during normal lifecycle windows: lock screen (PIN not
        // entered), brief startup race before wallet decryption, after
        // session clear on background. Without this guard, getAccountStatus
        // throws "No wallet" on every poll and floods logcat with stack
        // traces that look like real errors.
        if (!source.hasWalletInfo()) {
            return
        }

        source.getAccountStatus()
                    .onSuccess { status ->
                        // Generation gate: refuse to publish state if stopSyncPolling()
                        // (or a fresh start) has bumped the generation since this
                        // iteration began. Prevents in-flight responses that returned
                        // just before cancel() from resurrecting cleared state.
                        if (generation != syncPollingGeneration) return@onSuccess

                        val syncedBlock = status.syncedToBlock.toLongOrNull() ?: 0L
                        val tipBlock = status.tipNumber.toLongOrNull() ?: 0L

                        // Diagnostic for the production sync-stall reports (#150).
                        // Logged once every poll cycle so support can see the
                        // delta between syncedBlock and tipBlock in logcat
                        // without enabling verbose JNI logging.
                        logger.i(
                            TAG,
                            "syncPoll synced=$syncedBlock tip=$tipBlock " +
                                "delta=${tipBlock - syncedBlock} progress=${status.syncProgress}"
                        )

                        syncProgressTracker.recordSample(syncedBlock, System.currentTimeMillis())
                        val info = syncProgressTracker.calculate(tipBlock)

                        // Staleness pill input (#286): persist "last time we
                        // observed sync progress", throttled to ~1 write/min
                        // (the poll runs every 5-30s; pref churn is pointless).
                        val nowMs = System.currentTimeMillis()
                        if (syncedBlock > 0 && nowMs - lastSyncedAtWrittenMs > 60_000L) {
                            lastSyncedAtWrittenMs = nowMs
                            syncPreferences.setLastSyncedAt(nowMs)
                        }

                        val justReachedTip = wasSyncing && info.isSynced
                        wasSyncing = !info.isSynced

                        // Edge-track first time we entered "catching up" (actively
                        // downloading blocks) so HomeViewModel can apply a grace
                        // period before showing the sync coachmark (#90).
                        val catching = !info.isSynced && info.percentage < 100
                        firstCatchingUpAtMs = computeFirstCatchingUpAtMs(
                            firstCatchingUpAtMs,
                            catching,
                            System.currentTimeMillis()
                        )

                        _syncProgress.value = SyncProgress(
                            isSyncing = !info.isSynced,
                            syncedToBlock = syncedBlock,
                            tipBlockNumber = tipBlock,
                            percentage = info.percentage,
                            etaDisplay = info.etaDisplay,
                            justReachedTip = justReachedTip,
                            firstCatchingUpAtMs = firstCatchingUpAtMs
                        )
                    }
                    .onFailure { e ->
                        logger.e(TAG, "Sync polling: failed to get account status", e)
                    }
    }

    /**
     * Stop centralized sync polling. Resets tracker state.
     */
    fun stop() {
        // Bump the generation FIRST so any in-flight onSuccess lambda sees a
        // mismatch and refuses to write before we clear state below.
        syncPollingGeneration++
        syncPollingJob?.cancel()
        syncPollingJob = null
        syncProgressTracker.reset()
        wasSyncing = false
        // Clear the coachmark grace tracker (#90) so a subsequent
        // startSyncPolling() restarts the 2s grace from a clean clock.
        // Also strip the timestamp from the last-emitted SyncProgress so
        // HomeViewModel's combine doesn't see stale state during the gap.
        firstCatchingUpAtMs = null
        _syncProgress.value = _syncProgress.value.copy(firstCatchingUpAtMs = null)
        logger.d(TAG, "Stopped centralized sync polling")
    }

    companion object {
        private const val TAG = "SyncPoller"
    }
}
