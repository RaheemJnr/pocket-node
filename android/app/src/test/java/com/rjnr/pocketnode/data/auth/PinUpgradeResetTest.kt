package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.recordStartupVersion
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real startup-version bookkeeping must not grant a new PIN guessing budget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PinUpgradeResetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("upgrade_test_pin", Context.MODE_PRIVATE)
    private var now = 1_000_000L
    private lateinit var pin: PinManager
    private lateinit var versions: WalletPreferences

    private fun reopen() {
        pin = PinManager(context, Blake2b(), NoopLogger).apply {
            testPrefs = context.getSharedPreferences("upgrade_test_pin", Context.MODE_PRIVATE)
            timeProvider = { now }
            argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
        }
        versions = WalletPreferences(context, NoopLogger)
    }

    @Before fun setUp() {
        prefs.edit().clear().commit()
        reopen()
        versions.setLastSeenVersionCode(19)
        pin.setPin("123456")
    }

    private fun startVersion(current: Int) {
        recordStartupVersion(versions, current)
        // Flush the SharedPreferences queue before reconstructing the managers.
        assertTrue(prefs.edit().commit())
        reopen()
    }

    @Test fun `signed upgrade and later version jump preserve cumulative failures`() {
        repeat(3) { assertFalse(pin.verifyPin("000000")) }
        val failedAt = prefs.getLong("last_failed_at", -1)
        startVersion(20)
        assertEquals(20, versions.getLastSeenVersionCode())
        assertEquals(2, pin.getRemainingAttempts())
        assertEquals(failedAt, prefs.getLong("last_failed_at", -1))
        assertFalse(pin.verifyPin("000000"))
        startVersion(28)
        assertEquals(28, versions.getLastSeenVersionCode())
        assertEquals(1, pin.getRemainingAttempts())
    }

    @Test fun `upgrade preserves deadline and expired lockout continues escalating`() {
        repeat(5) { assertFalse(pin.verifyPin("000000")) }
        startVersion(20)
        assertTrue(pin.isLockedOut())
        assertEquals(30_000L, pin.getLockoutRemainingMs())
        assertEquals(1_030_000L, prefs.getLong("lockout_until", -1))
        assertFalse(pin.verifyPin("123456"))
        now += 10_000L
        startVersion(20)
        assertEquals(20_000L, pin.getLockoutRemainingMs())
        now = 1_030_001L
        assertFalse(pin.isLockedOut())
        assertFalse(pin.verifyPin("000000"))
        assertEquals(6, prefs.getInt("failed_attempts", -1))
        assertEquals(60_000L, pin.getLockoutRemainingMs())
    }

    @Test fun `fresh version metadata same version and downgrade preserve failure budget`() {
        repeat(3) { pin.verifyPin("000000") }
        versions.setLastSeenVersionCode(0)
        for (version in listOf(20, 20, 19)) {
            startVersion(version)
            assertEquals(version, versions.getLastSeenVersionCode())
            assertEquals(2, pin.getRemainingAttempts())
        }
    }

    @Test fun `correct PIN after timed lockout restores budget across restart`() {
        repeat(5) { pin.verifyPin("000000") }
        startVersion(20)
        now += 30_001L
        assertTrue(pin.verifyPin("123456"))
        startVersion(20)
        assertEquals(5, pin.getRemainingAttempts())
        assertFalse(pin.isLockedOut())
        assertFalse(pin.isPermanentlyLocked())
        assertEquals(0L, pin.getLockoutRemainingMs())
        assertFalse(prefs.contains("last_failed_at"))
        assertFalse(prefs.contains("lockout_until"))
    }

    @Test fun `upgrade cannot bypass existing recovery-required threshold`() {
        repeat(10) {
            now += pin.getLockoutRemainingMs() + 1
            assertFalse(pin.verifyPin("000000"))
        }
        assertTrue(pin.isPermanentlyLocked())
        startVersion(20)
        assertTrue(pin.isPermanentlyLocked())
        assertTrue(pin.isLockedOut())
        assertEquals(Long.MAX_VALUE, prefs.getLong("lockout_until", -1))
        assertFalse(pin.verifyPin("123456"))
    }
}
