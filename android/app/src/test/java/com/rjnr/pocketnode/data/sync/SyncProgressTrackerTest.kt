package com.rjnr.pocketnode.data.sync

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SyncProgressTrackerTest {

    private lateinit var tracker: SyncProgressTracker

    @Before
    fun setUp() {
        tracker = SyncProgressTracker()
    }

    @Test
    fun `null ETA with less than 2 samples`() {
        tracker.recordSample(100, 1000)
        val info = tracker.calculate(tipHeight = 1000)
        assertNull(info.etaSeconds)
        assertEquals("Calculating...", info.etaDisplay)
    }

    @Test
    fun `ETA calculation with multiple samples`() {
        // 100 blocks per second
        tracker.recordSample(1000, 0)
        tracker.recordSample(1100, 1000) // 100 blocks in 1s
        val info = tracker.calculate(tipHeight = 2000)

        assertNotNull(info.etaSeconds)
        // Remaining: 2000 - 1100 = 900 blocks at 100 blocks/sec = 9 seconds
        assertEquals(9L, info.etaSeconds)
        assertEquals(100.0, info.blocksPerSecond, 0.1)
    }

    @Test
    fun `percentage relative to startHeight`() {
        tracker.recordSample(500, 0)   // startHeight = 500
        tracker.recordSample(750, 1000)
        val info = tracker.calculate(tipHeight = 1000)

        // Range = 1000 - 500 = 500, progress = 750 - 500 = 250
        // Percentage = 250 / 500 = 0.5 = 50%
        assertEquals(50.0, info.percentage, 0.1)
    }

    @Test
    fun `100 percent when synced`() {
        tracker.recordSample(990, 0)
        tracker.recordSample(1000, 1000)
        val info = tracker.calculate(tipHeight = 1000)

        assertEquals(100.0, info.percentage, 0.1)
        assertTrue(info.isSynced)
    }

    @Test
    fun `zero blocks per second when no time elapsed`() {
        tracker.recordSample(100, 1000)
        tracker.recordSample(200, 1000) // same timestamp
        val info = tracker.calculate(tipHeight = 1000)

        assertEquals(0.0, info.blocksPerSecond, 0.01)
        assertNull(info.etaSeconds)
    }

    @Test
    fun `sliding window keeps max 10 samples`() {
        // Add 12 samples
        for (i in 0..11) {
            tracker.recordSample(100L + i * 10, i * 1000L)
        }
        val info = tracker.calculate(tipHeight = 500)
        // Should still work — only last 10 samples are kept
        assertNotNull(info.blocksPerSecond)
        assertTrue(info.blocksPerSecond > 0)
    }

    @Test
    fun `startHeight captured from first sample`() {
        tracker.recordSample(5000, 0)
        tracker.recordSample(5500, 1000)
        val info = tracker.calculate(tipHeight = 10000)

        // startHeight = 5000, range = 10000 - 5000 = 5000
        // progress = 5500 - 5000 = 500, percentage = 500/5000 = 10%
        assertEquals(10.0, info.percentage, 0.1)
    }

    @Test
    fun `formatEta seconds`() {
        assertEquals("~45s remaining", SyncProgressTracker.formatEta(45))
    }

    @Test
    fun `formatEta minutes`() {
        assertEquals("~5 min remaining", SyncProgressTracker.formatEta(300))
    }

    @Test
    fun `formatEta hours`() {
        assertEquals("~2 hr remaining", SyncProgressTracker.formatEta(7200))
    }

    @Test
    fun `reset clears all state`() {
        tracker.recordSample(100, 0)
        tracker.recordSample(200, 1000)
        tracker.reset()

        val info = tracker.calculate(tipHeight = 1000)
        assertNull(info.etaSeconds)
        assertEquals(0.0, info.percentage, 0.01)
    }

    @Test
    fun `synced within 10 blocks tolerance`() {
        tracker.recordSample(990, 0)
        tracker.recordSample(995, 1000)
        val info = tracker.calculate(tipHeight = 1000)

        assertTrue(info.isSynced)
    }

    @Test
    fun `seedStartHeight anchors percentage to registered start block (#150)`() {
        // Pre-#150 the first sample's blockHeight became the baseline.
        // For a 2021 wallet whose light client reports syncedToBlock=0
        // on the first poll cycle, that anchored to 0 and the percentage
        // was momentarily wrong. Seeding with the registered start block
        // (5_000_000 here) keeps the math truthful from sample #1.
        tracker.seedStartHeight(startBlock = 5_000_000L)
        tracker.recordSample(blockHeight = 5_000_100L, timestampMs = 0L)
        // Tip = 18M, baseline = 5M, current = 5_000_100. Range = 13M,
        // progress = 100 blocks → ~0.0008%.
        val info = tracker.calculate(tipHeight = 18_000_000L)
        assertTrue("expected positive but tiny progress, got ${info.percentage}", info.percentage > 0.0)
        assertTrue("expected < 1%, got ${info.percentage}", info.percentage < 1.0)
    }

    @Test
    fun `seedStartHeight ignored when called with zero`() {
        // Defensive: a missing sync_progress row resolves to lightStartBlock=0;
        // seeding with 0 would re-introduce the pre-#150 bug shape.
        tracker.seedStartHeight(startBlock = 0L)
        // No seed applied → first sample provides baseline.
        tracker.recordSample(blockHeight = 5_000_000L, timestampMs = 0L)
        tracker.recordSample(blockHeight = 5_000_100L, timestampMs = 1000L)
        val info = tracker.calculate(tipHeight = 18_000_000L)
        // Baseline = first sample (5M), range = 13M, progress = 100 → tiny.
        assertTrue(info.percentage > 0.0 && info.percentage < 1.0)
    }

    @Test
    fun `seedStartHeight tolerates no-sample state`() {
        // Edge case: seed then ask immediately. No crash; percentage clamps
        // to 0 because currentHeight is 0 < startHeight.
        tracker.seedStartHeight(startBlock = 5_000_000L)
        val info = tracker.calculate(tipHeight = 18_000_000L)
        assertEquals(0.0, info.percentage, 0.01)
    }
}
