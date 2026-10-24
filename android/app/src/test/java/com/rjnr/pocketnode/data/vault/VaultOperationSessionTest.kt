package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class VaultOperationSessionTest {
    @Test fun `background cancels an ordinary operation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val running = launch { VaultOperationSession.run(controller.get()) { awaitCancellation() } }
            runCurrent(); controller.pause().stop(); runCurrent()
            assertTrue(running.isCancelled)
            running.cancelAndJoin()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `trusted credential handoff waits for foreground before returning`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val callback = CompletableDeferred<ByteArray>()
            var returned = false
            val running = launch { VaultOperationSession.run(controller.get()) { session ->
                session.systemAuthentication(discard = { it: ByteArray -> it.fill(0) }) { callback.await() }
                returned = true
            } }
            runCurrent(); controller.pause().stop(); callback.complete(byteArrayOf(5)); runCurrent()
            assertFalse(returned); assertTrue(running.isActive)
            controller.start().resume(); runCurrent(); running.join()
            assertTrue(returned)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `credential callback while user stays backgrounded discards secret`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val callback = CompletableDeferred<ByteArray>(); val secret = byteArrayOf(5)
            var returned = false
            val running = launch { VaultOperationSession.run(controller.get()) { session ->
                session.systemAuthentication(discard = { it: ByteArray -> it.fill(0) }) { callback.await() }
                returned = true
            } }
            runCurrent(); controller.pause().stop(); callback.complete(secret); advanceUntilIdle()
            assertTrue(running.isCancelled); assertFalse(returned); assertArrayEquals(byteArrayOf(0), secret)
        } finally { Dispatchers.resetMain() }
    }
}
