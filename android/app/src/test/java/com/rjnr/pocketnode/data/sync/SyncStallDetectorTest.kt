package com.rjnr.pocketnode.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncStallDetectorTest {

    private lateinit var detector: SyncStallDetector

    @Before
    fun setUp() {
        // 5 min threshold matches production default
        detector = SyncStallDetector(stallThresholdMs = 5L * 60L * 1000L)
    }

    @Test
    fun `first call never reports stalled`() {
        val info = detector.evaluate(syncedBlock = 5_000_000, tipBlock = 10_000_000, nowMs = 0L)
        assertFalse(info.isStalled)
        assertEquals(0L, info.stalledForMs)
    }

    @Test
    fun `not stalled while advancing`() {
        detector.evaluate(5_000_000, 10_000_000, 0L)
        detector.evaluate(5_100_000, 10_000_000, 60_000L)
        val info = detector.evaluate(5_200_000, 10_000_000, 120_000L)
        assertFalse(info.isStalled)
    }

    @Test
    fun `stalled after threshold with no advance`() {
        detector.evaluate(5_000_000, 10_000_000, 0L)
        // Same block 6 minutes later
        val info = detector.evaluate(5_000_000, 10_000_000, 6L * 60L * 1000L)
        assertTrue(info.isStalled)
        assertEquals(6L, info.stalledForMinutes)
    }

    @Test
    fun `not stalled below threshold`() {
        detector.evaluate(5_000_000, 10_000_000, 0L)
        // Same block 4 minutes later — under 5 min threshold
        val info = detector.evaluate(5_000_000, 10_000_000, 4L * 60L * 1000L)
        assertFalse(info.isStalled)
        assertEquals(4L, info.stalledForMinutes)
    }

    @Test
    fun `advance after long stall clears the flag`() {
        detector.evaluate(5_000_000, 10_000_000, 0L)
        val stalled = detector.evaluate(5_000_000, 10_000_000, 6L * 60L * 1000L)
        assertTrue(stalled.isStalled)

        val recovered = detector.evaluate(5_100_000, 10_000_000, 7L * 60L * 1000L)
        assertFalse(recovered.isStalled)
        assertEquals(0L, recovered.stalledForMs)
    }

    @Test
    fun `synced within tolerance never stalls`() {
        // syncedBlock within SYNC_TOLERANCE (10) of tip — treat as synced
        detector.evaluate(9_999_995, 10_000_000, 0L)
        val info = detector.evaluate(9_999_995, 10_000_000, 10L * 60L * 1000L)
        assertFalse(info.isStalled)
    }

    @Test
    fun `tipBlock zero treated as not-synced still stalls`() {
        // tip=0 is a transient state during peer warm-up; don't suppress stall detection
        detector.evaluate(0L, 0L, 0L)
        val info = detector.evaluate(0L, 0L, 10L * 60L * 1000L)
        assertTrue(info.isStalled)
    }

    @Test
    fun `reset clears all state`() {
        detector.evaluate(5_000_000, 10_000_000, 0L)
        detector.evaluate(5_000_000, 10_000_000, 6L * 60L * 1000L)
        detector.reset()

        val info = detector.evaluate(5_000_000, 10_000_000, 10L * 60L * 1000L)
        assertFalse(info.isStalled)
        assertEquals(0L, info.stalledForMs)
    }

    @Test
    fun `negative time delta coerced to zero`() {
        detector.evaluate(5_000_000, 10_000_000, 1000L)
        val info = detector.evaluate(5_000_000, 10_000_000, 500L)
        assertEquals(0L, info.stalledForMs)
        assertFalse(info.isStalled)
    }
}
