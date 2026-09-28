package com.rjnr.pocketnode.ui.screens.auth

import com.rjnr.pocketnode.data.auth.PinManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** #524 R5-2: typed PIN digits and a confirm-step candidate never survive a lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class PinViewModelLockTest {

    private val testDispatcher = StandardTestDispatcher()
    private val pinManager = mockk<PinManager>(relaxed = true).apply {
        every { isLockedOut() } returns false
        every { getRemainingAttempts() } returns PinManager.MAX_ATTEMPTS
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a lock clears typed digits and the setup PIN held for confirm`() = runTest {
        val vm = PinViewModel(pinManager, mockk(relaxed = true))
        vm.setMode(PinMode.CONFIRM)
        vm.setSetupPin("123456")
        advanceUntilIdle()
        vm.onDigitEntered('1')
        vm.onDigitEntered('2')
        assertEquals("12", vm.uiState.value.enteredDigits)

        ReauthLockEvents.onLocked()
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.enteredDigits)
        // The candidate is gone: a full PIN now fails to match instead of
        // completing the change.
        "123456".forEach { vm.onDigitEntered(it) }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pinComplete)
    }
}
