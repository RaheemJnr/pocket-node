package com.rjnr.pocketnode.ui.screens.onboarding

import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.ui.util.UiMessage
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [OnboardingViewModel]'s persist-error-mapping logic introduced
 * in #289 chunk 5.
 *
 * The full VM lifecycle (viewModelScope + suspend + value-class `Result`)
 * is hard to drive deterministically with MockK 1.13.16 — there is a known
 * boxing bug with inline value class returns that surfaces as a
 * ClassCastException at `result.onSuccess` / `.onFailure` consumption.
 * The pure-function tests below cover the user-visible contract:
 *
 *   - Cancelled → silent (null) — Test #6 invariant: no user-facing error
 *     when the user dismisses the BiometricPrompt intentionally. Combined
 *     with the AddWalletViewModelTest #8 assertion that the active-wallet
 *     hook isn't fired on the Cancelled path, the no-half-state-leak
 *     contract is fully pinned.
 *   - AuthError / WriteFailed / KeyInvalidated → distinct, user-meaningful
 *     messages. KeyInvalidated specifically tells the user to re-import,
 *     since the V2 Keystore key has been wiped by biometric enrollment
 *     change — recovery requires the seed phrase.
 */
class OnboardingViewModelTest {

    @Test
    fun `persistErrorMessage maps Cancelled to null (silent — Test #6)`() {
        val msg = OnboardingViewModel.persistErrorMessage(
            WalletKeyWriter.PersistException(WalletKeyWriter.Result.Cancelled)
        )
        assertNull("Cancelled must be silent (no error toast)", msg)
    }

    @Test
    fun `persistErrorMessage maps KeyInvalidated to re-import prompt`() {
        val msg = OnboardingViewModel.persistErrorMessage(
            WalletKeyWriter.PersistException(WalletKeyWriter.Result.KeyInvalidated)
        )
        assertNotNull(msg)
        assertTrue(
            "KeyInvalidated should mention re-import",
            (msg as UiMessage.Raw).text.contains("re-imported")
        )
    }

    @Test
    fun `persistErrorMessage maps AuthError to a non-silent auth message`() {
        val msg = OnboardingViewModel.persistErrorMessage(
            WalletKeyWriter.PersistException(
                WalletKeyWriter.Result.AuthError(7, "biometric not recognised")
            )
        )
        assertNotNull(msg)
        assertTrue((msg as UiMessage.Raw).text.contains("Auth error"))
        assertTrue(msg.text.contains("biometric not recognised"))
    }

    @Test
    fun `persistErrorMessage maps WriteFailed to user-readable save error`() {
        val msg = OnboardingViewModel.persistErrorMessage(
            WalletKeyWriter.PersistException(
                WalletKeyWriter.Result.WriteFailed(IllegalStateException("disk full"))
            )
        )
        assertNotNull(msg)
        assertTrue((msg as UiMessage.Raw).text.contains("Failed to save"))
        assertTrue(msg.text.contains("disk full"))
    }

    @Test
    fun `persistErrorMessage falls through for non-PersistException causes`() {
        val msg = OnboardingViewModel.persistErrorMessage(
            IllegalArgumentException("name already taken")
        )
        assertNotNull(msg)
        assertEquals("name already taken", (msg as UiMessage.Raw).text)
    }
}
