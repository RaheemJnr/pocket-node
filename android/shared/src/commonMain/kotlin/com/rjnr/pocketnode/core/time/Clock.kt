package com.rjnr.pocketnode.core.time

/**
 * Wall-clock milliseconds since the Unix epoch, injected rather than read off
 * a platform global.
 *
 * `System.currentTimeMillis()` does not exist in `commonMain`, and the two
 * places the sync loop reads the clock are exactly the two places worth
 * testing: the 60 s throttle on the `lastSyncedAt` pref write (#286) and the
 * `firstCatchingUpAtMs` coachmark edge (#90). A one-method interface keeps
 * both under a fake clock in `commonTest` and keeps the shared core free of
 * `expect`/`actual` (D2 in docs/IOS_M1_DESIGN.md).
 *
 * Implementations must be safe to call from any thread.
 */
fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    fun nowMs(): Long
}

/**
 * The real clock. Identical in value to `System.currentTimeMillis()` on
 * Android and to `Date().timeIntervalSince1970 * 1000` on iOS: both platforms
 * answer [kotlin.time.Clock.System] from the same system wall clock, so it is
 * subject to the same NTP steps and user changes the previous
 * `System.currentTimeMillis()` calls were.
 *
 * No `@OptIn(ExperimentalTime::class)`: `kotlin.time.Clock` is stable as of
 * the Kotlin 2.3.10 stdlib this module builds against.
 */
object SystemClock : Clock {
    override fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
