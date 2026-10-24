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
    @Test @Config(sdk = [30]) fun `trusted credential handoff waits for foreground before returning`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val callback = CompletableDeferred<ByteArray>()
            var returned = false
            val running = launch { VaultOperationSession.run(controller.get()) { session ->
                session.systemAuthentication(VaultDevicePromptMode.CREDENTIAL_ONLY, discard = { it: ByteArray -> it.fill(0) }) { callback.await() }
                returned = true
            } }
            runCurrent(); controller.pause().stop(); callback.complete(byteArrayOf(5)); runCurrent()
            assertFalse(returned); assertTrue(running.isActive)
            controller.start().resume(); runCurrent(); running.join()
            assertTrue(returned)
        } finally { Dispatchers.resetMain() }
    }
    @Test @Config(sdk = [30]) fun `credential callback while user stays backgrounded discards secret`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val callback = CompletableDeferred<ByteArray>(); val secret = byteArrayOf(5)
            var returned = false
            val running = launch { VaultOperationSession.run(controller.get()) { session ->
                session.systemAuthentication(VaultDevicePromptMode.CREDENTIAL_ONLY, discard = { it: ByteArray -> it.fill(0) }) { callback.await() }
                returned = true
            } }
            runCurrent(); controller.pause().stop(); callback.complete(secret); advanceUntilIdle()
            assertTrue(running.isCancelled); assertFalse(returned); assertArrayEquals(byteArrayOf(0), secret)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `pending biometric authentication cancels immediately on background`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val started = CompletableDeferred<Unit>()
            val running = launch { VaultOperationSession.run(controller.get()) { session ->
                session.systemAuthentication(VaultDevicePromptMode.BIOMETRIC_ONLY) { started.complete(Unit); awaitCancellation() }
            } }
            try {
                runCurrent(); started.await(); controller.pause().stop(); runCurrent()
                assertTrue(running.isCancelled)
            } finally { running.cancelAndJoin() }
        } finally { Dispatchers.resetMain() }
    }
    @Test @Config(sdk = [30]) fun `pending credential handoff has finite timeout and clears operation owner`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val secret = charArrayOf('s')
            val running = launch { VaultOperationSession.run(controller.get()) { session ->
                try { session.systemAuthentication(VaultDevicePromptMode.CREDENTIAL_ONLY) { awaitCancellation() } }
                finally { secret.fill('\u0000') }
            } }
            try {
                runCurrent(); controller.pause().stop(); advanceTimeBy(60_001); runCurrent()
                assertTrue(running.isCancelled); assertArrayEquals(charArrayOf('\u0000'), secret)
            } finally { running.cancelAndJoin() }
        } finally { Dispatchers.resetMain() }
    }

    @Test @Config(sdk = [30]) fun `nested credential owner delegates only active handoff to outer owner`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val callback = CompletableDeferred<Unit>()
            var authenticated = false
            val running = launch { VaultOperationSession.run(controller.get()) {
                VaultOperationSession.run(controller.get()) { inner ->
                    inner.systemAuthentication(VaultDevicePromptMode.CREDENTIAL_ONLY) { callback.await() }
                }
                authenticated = true
                awaitCancellation()
            } }
            runCurrent(); controller.pause().stop(); callback.complete(Unit); runCurrent()
            assertTrue("outer must permit the nested trusted prompt", running.isActive)
            controller.start().resume(); runCurrent(); assertTrue(authenticated)
            controller.pause().stop(); runCurrent()
            assertTrue("ordinary stop after callback still cancels", running.isCancelled)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `reauth cancels owner and clears operation buffer`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val secret = byteArrayOf(9)
            val running = launch { VaultOperationSession.run(controller.get()) {
                try { awaitCancellation() } finally { secret.fill(0) }
            } }
            runCurrent(); com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked(); runCurrent()
            try { assertTrue(running.isCancelled); assertArrayEquals(byteArrayOf(0), secret) }
            finally { running.cancelAndJoin() }
        } finally { Dispatchers.resetMain() }
    }
    @Test @Config(sdk = [30]) fun `nested credential exemption never covers a sibling operation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val callback = CompletableDeferred<Unit>(); var siblingCancelled = false
            val running = launch { VaultOperationSession.run(controller.get()) {
                kotlinx.coroutines.coroutineScope {
                    launch { VaultOperationSession.run(controller.get()) {
                        try { awaitCancellation() } finally { siblingCancelled = true }
                    } }
                    VaultOperationSession.run(controller.get()) { inner ->
                        inner.systemAuthentication(VaultDevicePromptMode.CREDENTIAL_ONLY) { callback.await() }
                    }
                }
            } }
            runCurrent(); controller.pause().stop(); runCurrent()
            assertTrue(siblingCancelled); assertTrue(running.isActive)
            callback.complete(Unit); controller.start().resume(); runCurrent(); running.join()
        } finally { Dispatchers.resetMain() }
    }


}
