package com.rjnr.pocketnode.ui.screens.auth

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class AuthResultAuthorizationTest {
    @Test fun `saved PIN success signal cannot authorize without a live credential session`() {
        val auth = mockk<AuthManager>(relaxed = true)
        every { auth.hasSessionPin() } returns false
        val vm = AuthViewModel(auth, mockk(relaxed = true), mockk(), mockk(), mockk(), NoopLogger)
        vm.onPinUnlockSuccess()
        assertFalse(vm.uiState.value.authSuccess)
    }

    @Test fun `current verified PIN session accepts the UI success signal`() {
        val auth = mockk<AuthManager>(relaxed = true)
        every { auth.hasSessionPin() } returns true
        val vm = AuthViewModel(auth, mockk(relaxed = true), mockk(), mockk(), mockk(), NoopLogger)
        vm.onPinUnlockSuccess()
        assertTrue(vm.uiState.value.authSuccess)
    }
}
