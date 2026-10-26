package com.rjnr.pocketnode.ui.screens.auth

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PinSetupAuthorizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = PinManager(context, Blake2b(), NoopLogger).apply {
        testPrefs = context.getSharedPreferences("entry_pin", Context.MODE_PRIVATE)
        argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
    }
    private val auth = AuthManager(context)

    private fun confirm(pin: String): PinViewModel {
        val vm = PinViewModel(manager, auth)
        vm.setMode(PinMode.CONFIRM)
        vm.setSetupPin(pin)
        pin.forEach(vm::onDigitEntered)
        val deadline = System.nanoTime() + 5_000_000_000
        while (vm.uiState.value.isVerifying && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("PIN operation completed", vm.uiState.value.isVerifying)
        return vm
    }

    @Test fun `unauthenticated setup cannot replace existing PIN or grant session`() {
        manager.setPin("123456")
        val vm = confirm("654321")
        assertFalse(vm.uiState.value.pinComplete)
        assertTrue(manager.verifyPin("123456"))
        assertFalse(manager.verifyPin("654321"))
        assertFalse(auth.hasSessionPin())
    }

    @Test fun `setup handoff requires current session and rejects it after revocation`() {
        manager.setPin("123456")
        val vm = PinViewModel(manager, auth)
        assertTrue(vm.hasPersistedPin())
        assertFalse(vm.canCompleteInitialSetup())
        auth.setSessionPin("123456".toCharArray())
        assertTrue(vm.canCompleteInitialSetup())
        auth.clearSession()
        assertFalse(vm.canCompleteInitialSetup())
    }

    @Test fun `first PIN setup creates credential and session`() {
        val vm = confirm("654321")
        assertTrue(vm.uiState.value.pinComplete)
        assertTrue(manager.verifyPin("654321"))
        assertTrue(auth.hasSessionPin())
    }
    @Test fun `PIN verification completing after lock cannot restore a session`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val slow = io.mockk.mockk<PinManager>(relaxed = true)
        io.mockk.every { slow.verifyPin(any()) } answers {
            started.countDown()
            finish.await(5, java.util.concurrent.TimeUnit.SECONDS)
            true
        }
        val vm = PinViewModel(slow, auth)
        vm.setMode(PinMode.VERIFY)
        "123456".forEach(vm::onDigitEntered)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        ReauthLockEvents.onLocked()
        shadowOf(Looper.getMainLooper()).idle()
        finish.countDown()
        val deadline = System.nanoTime() + 5_000_000_000
        while (vm.uiState.value.isVerifying && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(vm.uiState.value.isVerifying)
        assertFalse(vm.uiState.value.pinComplete)
        assertFalse(auth.hasSessionPin())
    }

    @Test fun `first PIN completion after background cannot grant a session`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val slow = io.mockk.mockk<PinManager>(relaxed = true)
        io.mockk.every { slow.setPin(any()) } answers {
            started.countDown()
            finish.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        }
        io.mockk.every { slow.verifyPin("123456") } returns true
        val vm = PinViewModel(slow, auth)
        vm.setMode(PinMode.CONFIRM)
        vm.setSetupPin("123456")
        "123456".forEach(vm::onDigitEntered)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val policy = com.rjnr.pocketnode.ReauthLockPolicy(
            ReauthGate(androidx.compose.runtime.mutableStateOf(false), androidx.compose.runtime.mutableIntStateOf(0)),
            com.rjnr.pocketnode.ProcessUnlockState(),
            onNewLock = { ReauthLockEvents.onLocked() }, clearSessionSecrets = { auth.clearSession() })
        policy.onStop(listOf("initial_pin_setup"), hasWallet = false, hasPin = false, isChangingConfigurations = false)
        finish.countDown()
        val deadline = System.nanoTime() + 5_000_000_000
        while (vm.uiState.value.isVerifying && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(vm.uiState.value.isVerifying)
        assertFalse(vm.uiState.value.pinComplete)
        assertFalse(auth.hasSessionPin())
        assertEquals(PinMode.VERIFY, vm.uiState.value.mode)
        // A completion that arrives after foreground return still requires fresh PIN entry.
        "123456".forEach(vm::onDigitEntered)
        val retryDeadline = System.nanoTime() + 5_000_000_000
        while (vm.uiState.value.isVerifying && System.nanoTime() < retryDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(vm.uiState.value.pinComplete)
        assertTrue(auth.hasSessionPin())
    }

}
