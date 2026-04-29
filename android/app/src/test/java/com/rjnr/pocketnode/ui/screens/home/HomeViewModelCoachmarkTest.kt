package com.rjnr.pocketnode.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelCoachmarkTest {

    @Test
    fun `flag false plus catching up plus 2s elapsed shows coachmark`() {
        val show = HomeViewModel.shouldShowSyncCoachmark(
            seen = false,
            isCatchingUp = true,
            firstCatchingUpAtMs = 8_000L,
            nowMs = 10_000L,
        )
        assertTrue(show)
    }

    @Test
    fun `flag false plus catching up plus 1s elapsed under grace does not show`() {
        val show = HomeViewModel.shouldShowSyncCoachmark(
            seen = false,
            isCatchingUp = true,
            firstCatchingUpAtMs = 9_000L,
            nowMs = 10_000L,
        )
        assertFalse(show)
    }

    @Test
    fun `flag false plus sync done does not show`() {
        val show = HomeViewModel.shouldShowSyncCoachmark(
            seen = false,
            isCatchingUp = false,
            firstCatchingUpAtMs = null,
            nowMs = 10_000L,
        )
        assertFalse(show)
    }

    @Test
    fun `flag true never shows`() {
        val show = HomeViewModel.shouldShowSyncCoachmark(
            seen = true,
            isCatchingUp = true,
            firstCatchingUpAtMs = 0L,
            nowMs = 10_000L,
        )
        assertFalse(show)
    }

    @Test
    fun `negative elapsed delta is treated as zero`() {
        // Defensive: clock skew or test fixture with frozen now < firstCatchingUpAtMs.
        // Should not crash and should not show (delta < 2s grace).
        val show = HomeViewModel.shouldShowSyncCoachmark(
            seen = false,
            isCatchingUp = true,
            firstCatchingUpAtMs = 100_000L,
            nowMs = 50_000L,
        )
        assertFalse(show)
    }

    @Test
    fun `firstCatchingUpAtMs null with isCatchingUp true does not show`() {
        // SyncProgress could theoretically emit isCatchingUp = true with no
        // first-time stamp during a tight init race. Defensive: don't show.
        val show = HomeViewModel.shouldShowSyncCoachmark(
            seen = false,
            isCatchingUp = true,
            firstCatchingUpAtMs = null,
            nowMs = 10_000L,
        )
        assertFalse(show)
    }
}
