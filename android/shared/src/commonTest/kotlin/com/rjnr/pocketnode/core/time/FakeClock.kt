package com.rjnr.pocketnode.core.time

/**
 * Hand-cranked [Clock] for tests.
 *
 * Deliberately independent of the coroutines test scheduler's virtual time:
 * the poll cadence is virtual time, the throttles are wall-clock, and the
 * poller tests need to move them separately (and, in the throttle test, in
 * lockstep by hand).
 *
 * [now] defaults to a plausible epoch millisecond rather than 0 so the first
 * `nowMs() - lastWritten > 60_000` comparison behaves as it does in
 * production, where the clock is never near zero.
 */
class FakeClock(var now: Long = DEFAULT_START_MS) : Clock {

    override fun nowMs(): Long = now

    /** Move the clock forward by [ms]. */
    fun advance(ms: Long) {
        now += ms
    }

    companion object {
        /** 2026-11-14T22:13:20Z, an arbitrary but realistic starting point. */
        const val DEFAULT_START_MS: Long = 1_700_000_000_000L
    }
}
