package com.rjnr.pocketnode

/** Waits for a version-specific stable control; absence during navigation is not a native route. */
internal object ImportRouteSelector {
    enum class Route { LEGACY, NATIVE }
    fun await(timeoutMs: Long, now: () -> Long, pause: (Long) -> Unit,
              legacyVisible: () -> Boolean, nativeVisible: () -> Boolean): Route? {
        val started = now()
        while (true) {
            if (legacyVisible()) return Route.LEGACY
            if (nativeVisible()) return Route.NATIVE
            val remaining = timeoutMs - (now() - started)
            if (remaining <= 0) return null
            pause(minOf(100L, remaining))
        }
    }
}
