package com.rjnr.pocketnode.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressFirstCatchingUpTest {

    @Test
    fun `idle to idle stays null`() {
        assertNull(computeFirstCatchingUpAtMs(prev = null, catching = false, nowMs = 100L))
    }

    @Test
    fun `idle to catching captures now`() {
        assertEquals(100L, computeFirstCatchingUpAtMs(prev = null, catching = true, nowMs = 100L))
    }

    @Test
    fun `catching stays catching keeps original timestamp`() {
        assertEquals(100L, computeFirstCatchingUpAtMs(prev = 100L, catching = true, nowMs = 250L))
    }

    @Test
    fun `catching to idle resets to null`() {
        assertNull(computeFirstCatchingUpAtMs(prev = 100L, catching = false, nowMs = 250L))
    }

    @Test
    fun `idle then catching again starts a fresh clock`() {
        val a = computeFirstCatchingUpAtMs(prev = null, catching = true, nowMs = 100L)
        val b = computeFirstCatchingUpAtMs(prev = a, catching = false, nowMs = 200L)
        val c = computeFirstCatchingUpAtMs(prev = b, catching = true, nowMs = 300L)
        assertEquals(300L, c)
    }
}
