package com.rjnr.pocketnode.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class FaqRouteTest {

    @Test
    fun `route without anchor uses bare path`() {
        assertEquals("faq", Screen.Faq.routeWithAnchor(null))
    }

    @Test
    fun `route with blank anchor uses bare path`() {
        assertEquals("faq", Screen.Faq.routeWithAnchor(""))
        assertEquals("faq", Screen.Faq.routeWithAnchor("   "))
    }

    @Test
    fun `route with anchor encodes query`() {
        assertEquals("faq?anchor=sync_mode", Screen.Faq.routeWithAnchor("sync_mode"))
    }
}
