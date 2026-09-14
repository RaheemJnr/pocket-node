package com.rjnr.pocketnode.data.wallet

import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletPreferencesCoachmarkTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `default has not seen sync coachmark`() = runTest {
        val prefs = WalletPreferences(ctx, NoopLogger).also { it.clear() }
        assertFalse(prefs.hasSeenSyncCoachmarkFlow.first())
    }

    @Test
    fun `mark seen persists`() = runTest {
        val prefs = WalletPreferences(ctx, NoopLogger).also { it.clear() }
        prefs.markSyncCoachmarkSeen()
        assertTrue(prefs.hasSeenSyncCoachmarkFlow.first())

        // New instance reads same SharedPreferences file.
        val reread = WalletPreferences(ctx, NoopLogger)
        assertTrue(reread.hasSeenSyncCoachmarkFlow.first())
    }
}
