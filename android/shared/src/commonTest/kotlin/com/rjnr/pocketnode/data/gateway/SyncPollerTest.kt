package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.models.AccountStatusResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The poll loop's own behaviour, under a virtual coroutine clock for the
 * cadence and a hand-cranked [FakeClock] for the wall-clock throttles.
 *
 * These could not be written while [SyncPoller] lived in the app module and
 * read `System.currentTimeMillis()` directly: the 60 s `lastSyncedAt` throttle
 * and the `justReachedTip` edge were only reachable through a real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPollerTest {

    /**
     * Scripted [SyncPollSource]. [tip] and [syncedBlock] are read fresh on
     * every poll, so a test can flip the wallet from catching up to synced
     * between ticks. [onPoll] runs before the answer is returned, which is how
     * the stop-during-a-poll case is staged.
     */
    private class FakeSource(
        var tip: Long = 1_000L,
        var syncedBlock: Long = 500L,
        var hasWallet: Boolean = true,
        var onPoll: () -> Unit = {},
    ) : SyncPollSource {

        var pollCount: Int = 0
            private set

        override fun hasWalletInfo(): Boolean = hasWallet

        override suspend fun getAccountStatus(): Result<AccountStatusResponse> {
            pollCount++
            onPoll()
            return Result.success(
                AccountStatusResponse(
                    address = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq",
                    isRegistered = true,
                    tipNumber = tip.toString(),
                    syncedToBlock = syncedBlock.toString(),
                    syncProgress = 0.0,
                    isSynced = syncedBlock >= tip - 10,
                )
            )
        }
    }

    private fun poller(
        prefs: FakeSyncPreferences = FakeSyncPreferences(),
        clock: FakeClock = FakeClock(),
    ) = SyncPoller(prefs, NoopLogger, clock)

    @Test
    fun `start is idempotent`() = runTest {
        val source = FakeSource()
        val poller = poller()

        poller.start(backgroundScope, source)
        poller.start(backgroundScope, source)
        poller.start(backgroundScope, source)
        runCurrent()

        assertEquals(1, source.pollCount, "three starts must not stack three loops")

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, source.pollCount, "one loop, so one poll per cadence tick")
    }

    @Test
    fun `no poll while no wallet is loaded`() = runTest {
        val source = FakeSource(hasWallet = false)
        val poller = poller()

        poller.start(backgroundScope, source)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(0, source.pollCount)
        assertEquals(SyncProgress(), poller.syncProgress.value)
    }

    @Test
    fun `stop bumps the generation so a late response cannot write`() = runTest {
        val poller = poller()
        // The response comes back just as stopSyncPolling() runs: cancellation
        // is cooperative, so the onSuccess lambda still executes. Only the
        // generation gate stops it publishing into the cleared state.
        val source = FakeSource(tip = 1_000L, syncedBlock = 500L)
        source.onPoll = { poller.stop() }

        poller.start(backgroundScope, source)
        runCurrent()

        assertEquals(1, source.pollCount, "the poll did run")
        assertEquals(
            SyncProgress(),
            poller.syncProgress.value,
            "a stale-generation response must not resurrect cleared state",
        )
    }

    @Test
    fun `a same generation response does write`() = runTest {
        // Control for the test above: identical setup minus the stop() call.
        val source = FakeSource(tip = 1_000L, syncedBlock = 500L)
        val poller = poller()

        poller.start(backgroundScope, source)
        runCurrent()

        assertEquals(500L, poller.syncProgress.value.syncedToBlock)
        assertEquals(1_000L, poller.syncProgress.value.tipBlockNumber)
        assertTrue(poller.syncProgress.value.isSyncing)
    }

    @Test
    fun `justReachedTip fires exactly once on the synced edge`() = runTest {
        val source = FakeSource(tip = 1_000L, syncedBlock = 500L)
        val poller = poller()
        val edges = mutableListOf<Boolean>()

        poller.start(backgroundScope, source)
        runCurrent()
        edges += poller.syncProgress.value.justReachedTip // catching up

        source.syncedBlock = 1_000L
        advanceTimeBy(5_000)
        runCurrent()
        edges += poller.syncProgress.value.justReachedTip // the edge

        advanceTimeBy(10_000)
        runCurrent()
        edges += poller.syncProgress.value.justReachedTip // still synced

        advanceTimeBy(10_000)
        runCurrent()
        edges += poller.syncProgress.value.justReachedTip

        assertEquals(listOf(false, true, false, false), edges)
    }

    @Test
    fun `lastSyncedAt is written at most once a minute`() = runTest {
        val prefs = FakeSyncPreferences()
        val clock = FakeClock()
        val start = clock.now
        val source = FakeSource(tip = 1_000L, syncedBlock = 500L)
        val poller = poller(prefs, clock)

        poller.start(backgroundScope, source)
        runCurrent()
        assertEquals(listOf(start), prefs.lastSyncedAtWrites, "first poll always writes")

        // Thirteen more polls at the 5 s catching-up cadence: 65 s of wall
        // clock, so exactly one further write, on the tick that crosses 60 s.
        repeat(13) {
            clock.advance(5_000)
            advanceTimeBy(5_000)
            runCurrent()
        }

        assertEquals(14, source.pollCount)
        assertEquals(listOf(start, start + 65_000), prefs.lastSyncedAtWrites)
    }

    @Test
    fun `lastSyncedAt is not written before any block is synced`() = runTest {
        val prefs = FakeSyncPreferences()
        val source = FakeSource(tip = 1_000L, syncedBlock = 0L)
        val poller = poller(prefs)

        poller.start(backgroundScope, source)
        runCurrent()

        assertTrue(prefs.lastSyncedAtWrites.isEmpty())
    }

    @Test
    fun `cadence is 5s while syncing and 10s once synced`() = runTest {
        val source = FakeSource(tip = 1_000L, syncedBlock = 500L)
        val poller = poller()

        poller.start(backgroundScope, source)
        runCurrent()
        assertEquals(1, source.pollCount)

        // Catching up: a poll every 5 s.
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, source.pollCount, "no poll before 5 s")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, source.pollCount)

        // Reach the tip on the next poll; from then on the cadence is 10 s.
        source.syncedBlock = 1_000L
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, source.pollCount)
        assertFalse(poller.syncProgress.value.isSyncing)

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(3, source.pollCount, "no poll before 10 s once synced")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, source.pollCount)
    }

    @Test
    fun `stop clears the coachmark timestamp`() = runTest {
        val source = FakeSource(tip = 1_000L, syncedBlock = 500L)
        val poller = poller()

        poller.start(backgroundScope, source)
        runCurrent()
        assertTrue((poller.syncProgress.value.firstCatchingUpAtMs ?: 0L) > 0L)

        poller.stop()
        assertNull(poller.syncProgress.value.firstCatchingUpAtMs)
        // The rest of the last emission survives; only the timestamp is stripped.
        assertEquals(500L, poller.syncProgress.value.syncedToBlock)
    }

    @Test
    fun `a throwing source does not kill the loop`() = runTest {
        var failNext = true
        val source = object : SyncPollSource {
            var pollCount = 0
            override fun hasWalletInfo(): Boolean = true
            override suspend fun getAccountStatus(): Result<AccountStatusResponse> {
                pollCount++
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("JNI returned null")
                }
                return Result.success(
                    AccountStatusResponse(
                        address = "ckt1",
                        isRegistered = true,
                        tipNumber = "1000",
                        syncedToBlock = "1000",
                        syncProgress = 1.0,
                        isSynced = true,
                    )
                )
            }
        }
        val poller = poller()

        poller.start(backgroundScope, source)
        runCurrent()
        assertEquals(1, source.pollCount)

        // The failed poll published nothing, so isSyncing is still false and
        // the loop waits the synced cadence before trying again.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, source.pollCount, "the loop survived the throw")
        assertFalse(poller.syncProgress.value.isSyncing)
    }
}
