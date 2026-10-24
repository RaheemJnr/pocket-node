package com.rjnr.pocketnode

import org.junit.Assert.*
import org.junit.Test

class ImportRouteSelectorTest {
    @Test fun `delayed legacy navigation never selects native by absence`() {
        var now = 0L
        val route = ImportRouteSelector.await(1000, { now }, { now += it }, { now >= 300 }, { false })
        assertEquals(ImportRouteSelector.Route.LEGACY, route)
        assertTrue(now >= 300)
    }
    @Test fun `native control must actually appear before native route is selected`() {
        var now = 0L
        val route = ImportRouteSelector.await(1000, { now }, { now += it }, { false }, { now >= 400 })
        assertEquals(ImportRouteSelector.Route.NATIVE, route); assertTrue(now >= 400)
    }
    @Test fun `navigation timeout remains unknown instead of guessing a route`() {
        var now = 0L
        assertNull(ImportRouteSelector.await(500, { now }, { now += it }, { false }, { false }))
        assertEquals(500L, now)
    }
}
