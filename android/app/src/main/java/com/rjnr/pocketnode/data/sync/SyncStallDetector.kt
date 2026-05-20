package com.rjnr.pocketnode.data.sync

/**
 * Pure-logic detector for sync-stall: catches the production pathology where
 * `syncedToBlock` stops advancing for many minutes while the user sees 0%
 * (reported via Telegram for a 2021-era wallet, #150).
 *
 * Contract:
 *  - First [evaluate] call captures the baseline; never reports stalled.
 *  - Every advance (syncedBlock > lastObservedBlock) refreshes the baseline.
 *  - When syncedBlock has not advanced for >= [stallThresholdMs] AND we are
 *    not within [SYNC_TOLERANCE] of tip, [StallInfo.isStalled] is true.
 *  - When within tolerance of tip, baseline resets and stall flag clears.
 *
 * Thread-safety: NOT thread-safe. Called from a single sync-poll observer.
 */
class SyncStallDetector(
    private val stallThresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS,
) {

    data class StallInfo(
        val isStalled: Boolean,
        val stalledForMs: Long,
        val syncedBlock: Long,
        val tipBlock: Long,
    ) {
        val stalledForMinutes: Long get() = stalledForMs / 60_000L
    }

    private var lastObservedBlock: Long = -1L
    private var lastAdvanceAtMs: Long = 0L

    fun evaluate(syncedBlock: Long, tipBlock: Long, nowMs: Long): StallInfo {
        val isSynced = tipBlock > 0 && syncedBlock >= tipBlock - SYNC_TOLERANCE
        if (isSynced) {
            lastObservedBlock = syncedBlock
            lastAdvanceAtMs = nowMs
            return StallInfo(false, 0L, syncedBlock, tipBlock)
        }

        if (lastObservedBlock < 0) {
            lastObservedBlock = syncedBlock
            lastAdvanceAtMs = nowMs
            return StallInfo(false, 0L, syncedBlock, tipBlock)
        }

        if (syncedBlock > lastObservedBlock) {
            lastObservedBlock = syncedBlock
            lastAdvanceAtMs = nowMs
            return StallInfo(false, 0L, syncedBlock, tipBlock)
        }

        val stalledForMs = (nowMs - lastAdvanceAtMs).coerceAtLeast(0L)
        return StallInfo(
            isStalled = stalledForMs >= stallThresholdMs,
            stalledForMs = stalledForMs,
            syncedBlock = syncedBlock,
            tipBlock = tipBlock,
        )
    }

    fun reset() {
        lastObservedBlock = -1L
        lastAdvanceAtMs = 0L
    }

    companion object {
        const val DEFAULT_STALL_THRESHOLD_MS: Long = 5L * 60L * 1000L
        private const val SYNC_TOLERANCE: Long = 10L
    }
}
