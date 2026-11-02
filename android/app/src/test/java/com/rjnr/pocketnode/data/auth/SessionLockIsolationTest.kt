package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletStorageBoundary
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hold the installation monitor explicitly; no KDF duration or device speed assumptions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionLockIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val boundary = WalletStorageBoundary(context)
    private val auth = AuthManager(context)
    private val keys = KeyManager(context, MnemonicManager(), NoopLogger)

    private fun withHeldFiles(block: (release: () -> Unit) -> Unit) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val holder = worker.submit {
            boundary.files {
                held.countDown()
                check(release.await(10, SECONDS))
            }
        }
        try {
            assertTrue(held.await(2, SECONDS))
            block { release.countDown() }
        } finally {
            release.countDown()
            holder.get(2, SECONDS)
            worker.shutdownNow()
        }
    }

    /** A timeout fails the assertion without leaving the test/UI thread stuck in a monitor. */
    private fun <T> promptly(block: () -> T): T {
        val worker = Executors.newSingleThreadExecutor()
        return try { worker.submit(Callable { block() }).get(2, SECONDS) }
        finally { worker.shutdownNow() }
    }

    private fun <T> blockedPublisher(block: () -> T): FutureTask<T> {
        val task = FutureTask(Callable { block() })
        val thread = Thread(task, "session-publication-test")
        thread.start()
        val deadline = System.nanoTime() + SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED && !task.isDone && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals("publication must wait for the installation monitor", Thread.State.BLOCKED, thread.state)
        assertFalse(task.isDone)
        return task
    }

    @Test fun `session reads and generation do not wait for file work`() {
        auth.setSessionPin("123456".toCharArray())
        val generation = auth.sessionGeneration()
        withHeldFiles {
            promptly {
                assertArrayEquals("123456".toCharArray(), auth.getSessionPin())
                assertTrue(auth.hasSessionPin())
                assertEquals(generation, auth.sessionGeneration())
            }
        }
    }

    @Test fun `session revocation and subsequent reads do not wait for file work`() {
        auth.setSessionPin("123456".toCharArray())
        val generation = auth.sessionGeneration()
        withHeldFiles {
            promptly {
                auth.clearSession()
                assertNull(auth.getSessionPin())
                assertFalse(auth.hasSessionPin())
                assertEquals(generation + 1, auth.sessionGeneration())
            }
        }
    }

    @Test fun `fallback session clear does not wait for file work`() {
        keys.setSessionPin("123456".toCharArray())
        withHeldFiles {
            promptly {
                keys.clearSessionPin()
                assertNull(keys.sessionPin)
            }
        }
    }

    @Test fun `authentication queued behind file work cannot publish after revocation`() {
        val generation = auth.sessionGeneration()
        withHeldFiles { release ->
            val pending = blockedPublisher { auth.setSessionPinIfCurrent("123456".toCharArray(), generation) }
            promptly { auth.clearSession() }
            assertFalse(pending.isDone)
            release()
            assertFalse(pending.get(2, SECONDS))
            assertFalse(auth.hasSessionPin())
        }
    }

    @Test fun `reset clears sessions during file work and rejects every queued publisher`() {
        auth.setSessionPin("123456".toCharArray())
        keys.setSessionPin("123456".toCharArray())
        val generation = auth.sessionGeneration()
        val held = CountDownLatch(1)
        val reset = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val holder = worker.submit {
            boundary.files {
                held.countDown()
                check(reset.await(10, SECONDS))
                boundary.beginReset()
                keys.clearSessionPin()
                auth.clearSession()
                cleared.countDown()
                check(release.await(10, SECONDS))
            }
        }
        try {
            assertTrue(held.await(2, SECONDS))
            val direct = blockedPublisher { runCatching { auth.setSessionPin("654321".toCharArray()) }.exceptionOrNull() }
            val conditional = blockedPublisher { runCatching { auth.setSessionPinIfCurrent("654321".toCharArray(), generation) }.exceptionOrNull() }
            val fallback = blockedPublisher { runCatching { keys.setSessionPin("654321".toCharArray()) }.exceptionOrNull() }
            reset.countDown()
            assertTrue(cleared.await(2, SECONDS))
            promptly {
                assertNull(auth.getSessionPin())
                assertFalse(auth.hasSessionPin())
                assertNull(keys.sessionPin)
                assertEquals(generation + 1, auth.sessionGeneration())
            }
            assertFalse(direct.isDone); assertFalse(conditional.isDone); assertFalse(fallback.isDone)
            release.countDown()
            for (pending in listOf(direct, conditional, fallback)) {
                assertTrue(pending.get(2, SECONDS) is IllegalStateException)
            }
            assertFalse(auth.hasSessionPin())
            assertNull(keys.sessionPin)
        } finally {
            reset.countDown()
            release.countDown()
            holder.get(2, SECONDS)
            worker.shutdownNow()
        }
    }

    @Test fun `fallback session inspection returns a copy`() {
        keys.setSessionPin("123456".toCharArray())
        keys.sessionPin!!.fill('0')
        assertArrayEquals("123456".toCharArray(), keys.sessionPin)
    }
}
