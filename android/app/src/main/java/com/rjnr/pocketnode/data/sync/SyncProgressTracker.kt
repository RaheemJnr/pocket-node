package com.rjnr.pocketnode.data.sync

/**
 * Pure-logic tracker that calculates sync progress percentage and ETA
 * using a sliding window of block height samples.
 *
 * Thread-safety: NOT thread-safe. Callers must synchronize externally if used
 * from multiple coroutines (in practice, only called from a single polling loop).
 */
class SyncProgressTracker {

    data class ProgressInfo(
        val percentage: Double,
        val etaSeconds: Long?,
        val etaDisplay: String,
        val blocksPerSecond: Double,
        val currentHeight: Long,
        val tipHeight: Long,
        val isSynced: Boolean
    )

    private data class Sample(val blockHeight: Long, val timestampMs: Long)

    private val samples = mutableListOf<Sample>()
    private var startHeight: Long? = null

    /**
     * Seed the percentage baseline with the wallet's registered light-client
     * start block (from `sync_progress.lightStartBlockNumber`). Called once
     * per wallet activation, before the polling loop starts feeding samples.
     *
     * Previously the first sample's blockHeight was used as the baseline. On
     * a 2021 wallet that path silently anchors to whatever the light client
     * reports first — often 0 during peer warm-up — which makes
     * `percentage = (current - 0) / (tip - 0)` lie about real progress for
     * the first poll cycle. Seeding with the registered start block fixes
     * the math from sample #1.
     *
     * Safe to call multiple times; later calls overwrite the baseline only
     * when the new value is non-zero. This handles the wallet-switch case
     * where the persisted lightStartBlock may temporarily be unknown.
     */
    fun seedStartHeight(startBlock: Long) {
        if (startBlock > 0) {
            startHeight = startBlock
        }
    }

    /** Record a new block height observation. First call captures startHeight
     *  iff [seedStartHeight] wasn't called first. */
    fun recordSample(blockHeight: Long, timestampMs: Long) {
        if (startHeight == null) {
            startHeight = blockHeight
        }
        samples.add(Sample(blockHeight, timestampMs))
        // Keep sliding window of 10 samples
        if (samples.size > MAX_WINDOW) {
            samples.removeAt(0)
        }
    }

    /** Calculate current progress info given the network tip height. */
    fun calculate(tipHeight: Long): ProgressInfo {
        val currentHeight = samples.lastOrNull()?.blockHeight ?: 0L
        val start = startHeight ?: 0L

        // Percentage relative to startHeight
        val range = tipHeight - start
        val progress = currentHeight - start
        val percentage = if (range > 0) {
            ((progress.toDouble() / range) * 100).coerceIn(0.0, 100.0)
        } else {
            if (tipHeight > 0 && currentHeight >= tipHeight) 100.0 else 0.0
        }

        val isSynced = tipHeight > 0 && currentHeight >= tipHeight - SYNC_TOLERANCE

        // Rate from sliding window: need >= 2 samples
        val blocksPerSecond: Double
        val etaSeconds: Long?

        if (samples.size >= 2) {
            val oldest = samples.first()
            val newest = samples.last()
            val elapsedMs = newest.timestampMs - oldest.timestampMs
            val blocksProcessed = newest.blockHeight - oldest.blockHeight

            if (elapsedMs > 0 && blocksProcessed > 0) {
                blocksPerSecond = blocksProcessed.toDouble() / (elapsedMs / 1000.0)
                val remaining = tipHeight - newest.blockHeight
                etaSeconds = if (remaining > 0 && !isSynced) {
                    (remaining / blocksPerSecond).toLong()
                } else {
                    null
                }
            } else {
                blocksPerSecond = 0.0
                etaSeconds = null
            }
        } else {
            blocksPerSecond = 0.0
            etaSeconds = null
        }

        val etaDisplay = when {
            isSynced -> "Synced"
            etaSeconds != null -> formatEta(etaSeconds)
            else -> "Calculating..."
        }

        return ProgressInfo(
            percentage = percentage,
            etaSeconds = etaSeconds,
            etaDisplay = etaDisplay,
            blocksPerSecond = blocksPerSecond,
            currentHeight = currentHeight,
            tipHeight = tipHeight,
            isSynced = isSynced
        )
    }

    /** Clear all samples and startHeight. */
    fun reset() {
        samples.clear()
        startHeight = null
    }

    companion object {
        private const val MAX_WINDOW = 10
        private const val SYNC_TOLERANCE = 10L

        fun formatEta(seconds: Long): String = when {
            seconds < 60 -> "~${seconds}s remaining"
            seconds < 3600 -> "~${seconds / 60} min remaining"
            else -> "~${seconds / 3600} hr remaining"
        }
    }
}
