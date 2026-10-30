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
class PinChangeViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pins = PinManager(context, Blake2b(), NoopLogger).apply {
        testPrefs = context.getSharedPreferences("pin_change_vm", Context.MODE_PRIVATE)
        argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
        setPin("123456")
    }
    private val auth = AuthManager(context).apply { setSessionPin("123456".toCharArray()) }
    private val vm = PinChangeViewModel(pins, auth)
    private fun enter(pin: String) {
        pin.forEach(vm::digit)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (vm.uiState.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun `change flow verifies current PIN confirms new PIN then requires unlock`() {
        enter("123456")
        assertEquals(PinChangeStep.NEW, vm.uiState.value.step)
        enter("654321")
        assertEquals(PinChangeStep.CONFIRM, vm.uiState.value.step)
        enter("654321")
        assertTrue(vm.uiState.value.needsUnlock)
        assertFalse(auth.hasSessionPin())
        assertTrue(pins.verifyPin("654321"))
        assertFalse(pins.verifyPin("123456"))
    }

    @Test fun `wrong current PIN cannot reach new PIN step`() {
        enter("654321")
        assertEquals(PinChangeStep.CURRENT, vm.uiState.value.step)
        assertFalse(vm.uiState.value.needsUnlock)
        assertEquals(4, pins.getRemainingAttempts())
    }

    @Test fun `confirmation mismatch preserves old PIN`() {
        enter("123456"); enter("654321"); enter("111111")
        assertEquals(PinChangeStep.CONFIRM, vm.uiState.value.step)
        assertFalse(vm.uiState.value.needsUnlock)
        assertTrue(pins.verifyPin("123456"))
    }

    @Test fun `lock discards current PIN authorization and candidate`() {
        enter("123456"); enter("654321")
        auth.clearSession(); ReauthLockEvents.onLocked()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PinChangeStep.CURRENT, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.digits)
        enter("654321")
        assertFalse(vm.uiState.value.needsUnlock)
        assertTrue(pins.verifyPin("123456"))
    }

    @Test fun `restored change route has no authorization even with a new session`() {
        enter("123456"); enter("654321")
        val restored = PinChangeViewModel(pins, auth)
        assertEquals(PinChangeStep.CURRENT, restored.uiState.value.step)
        assertEquals("", restored.uiState.value.digits)
        assertFalse(restored.uiState.value.needsUnlock)
        assertTrue(pins.verifyPin("123456"))
    }
    @Test fun `post-claim persistence finishing after lock cannot publish success or session`() = latePersistence(true)
    @Test fun `post-claim generation revocation alone suppresses stale completion`() = latePersistence(false)

    private fun latePersistence(signalLock: Boolean) {
        enter("123456"); enter("654321")
        val preferences = pins.testPrefs!!
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        pins.testPrefs = object : android.content.SharedPreferences by preferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : android.content.SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        started.countDown(); check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return delegate.commit()
                    }
                }
            }
        }
        try {
            "654321".forEach(vm::digit)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            auth.clearSession(); if (signalLock) ReauthLockEvents.onLocked()
            shadowOf(Looper.getMainLooper()).idle()
        } finally { finish.countDown() }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (vm.uiState.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertFalse(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.needsUnlock)
        assertEquals(PinChangeStep.CURRENT, vm.uiState.value.step)
        assertFalse(auth.hasSessionPin())
        assertTrue(pins.verifyPin("654321"))
    }

    @Test fun `biometric dashboard user can verify current PIN and complete change`() {
        auth.clearSession()
        enter("123456")
        assertEquals(PinChangeStep.NEW, vm.uiState.value.step)
        enter("654321"); enter("654321")
        assertTrue(vm.uiState.value.needsUnlock)
        assertFalse(auth.hasSessionPin())
        assertTrue(pins.verifyPin("654321"))
    }

}
