package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveredProgressTest {
    @Test fun `parent without a row copies nothing`() =
        assertNull(DiscoveredProgress.childStart(null, 120_000L, 100_000L))

    @Test fun `cursor below parent wins`() =
        assertEquals(120_000L, DiscoveredProgress.childStart(299_000L, 120_000L, 100_000L))

    @Test fun `cursor above parent keeps parent height`() =
        assertEquals(299_000L, DiscoveredProgress.childStart(299_000L, 350_000L, 100_000L))

    @Test fun `missing cursor falls back to registered from`() =
        assertEquals(100_000L, DiscoveredProgress.childStart(299_000L, null, 100_000L))

    @Test fun `both unknown copies nothing`() =
        assertNull(DiscoveredProgress.childStart(299_000L, null, 0L))

    @Test fun `zero values count as unknown`() {
        assertNull(DiscoveredProgress.childStart(299_000L, 0L, 0L))
        assertEquals(100_000L, DiscoveredProgress.childStart(299_000L, 0L, 100_000L))
    }
}
