package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.Blake2b
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PinManagerTest {

    private lateinit var pinManager: PinManager
    private lateinit var blake2b: Blake2b
    private var fakeTimeMs: Long = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        blake2b = Blake2b()
        pinManager = PinManager(context, blake2b)
        pinManager.testPrefs = context.getSharedPreferences("test_pin", Context.MODE_PRIVATE)
        pinManager.timeProvider = { fakeTimeMs }
        // Speed up Argon2id for unit tests. Production uses 64 MB / 3 iter / 4 lanes.
        pinManager.argon2Iterations = 1
        pinManager.argon2MemoryKb = 8
        pinManager.argon2Parallelism = 1
        pinManager.testPrefs!!.edit().clear().commit()
    }

    // -- PIN set/verify --

    @Test
    fun `setPin and verifyPin with correct PIN returns true`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.verifyPin("123456"))
    }

    @Test
    fun `verifyPin with wrong PIN returns false`() {
        pinManager.setPin("123456")
        assertFalse(pinManager.verifyPin("654321"))
    }

    @Test
    fun `hasPin returns false initially`() {
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `hasPin returns true after setPin`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.hasPin())
    }

    @Test
    fun `removePin clears stored PIN`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.hasPin())
        pinManager.removePin()
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `verifyPin returns false when no PIN set`() {
        assertFalse(pinManager.verifyPin("123456"))
    }

    // -- PIN validation --

    @Test(expected = IllegalArgumentException::class)
    fun `setPin rejects PIN shorter than 6 digits`() {
        pinManager.setPin("12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin rejects PIN longer than 6 digits`() {
        pinManager.setPin("1234567")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin rejects non-digit characters`() {
        pinManager.setPin("12345a")
    }

    // -- Hash consistency --

    @Test
    fun `same PIN always produces same hash`() {
        pinManager.setPin("111111")
        assertTrue(pinManager.verifyPin("111111"))
        assertTrue(pinManager.verifyPin("111111"))
        assertTrue(pinManager.verifyPin("111111"))
    }

    @Test
    fun `changing PIN updates hash`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.verifyPin("123456"))
        pinManager.setPin("654321")
        assertFalse(pinManager.verifyPin("123456"))
        assertTrue(pinManager.verifyPin("654321"))
    }

    // -- Lockout cycle --

    @Test
    fun `getRemainingAttempts starts at MAX_ATTEMPTS`() {
        pinManager.setPin("123456")
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
    }

    @Test
    fun `failed attempts decrement remaining attempts`() {
        pinManager.setPin("123456")
        pinManager.verifyPin("000000")
        assertEquals(PinManager.MAX_ATTEMPTS - 1, pinManager.getRemainingAttempts())
    }

    @Test
    fun `lockout triggers after MAX_ATTEMPTS failed attempts`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())
        assertEquals(0, pinManager.getRemainingAttempts())
    }

    @Test
    fun `verifyPin returns false during lockout even with correct PIN`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())
        assertFalse(pinManager.verifyPin("123456"))
    }

    @Test
    fun `lockout expires after LOCKOUT_DURATION_MS but counter persists`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())

        // After the lockout window, the user can try again, but the counter
        // is still at 5 — so the next failure escalates to the 1-minute lockout.
        fakeTimeMs += PinManager.LOCKOUT_DURATION_MS + 1
        assertFalse(pinManager.isLockedOut())
        assertEquals(0, pinManager.getRemainingAttempts())
    }

    @Test
    fun `getLockoutRemainingMs returns correct remaining time`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        val remaining = pinManager.getLockoutRemainingMs()
        assertTrue(remaining > 0)
        assertTrue(remaining <= PinManager.LOCKOUT_DURATION_MS)
    }

    @Test
    fun `getLockoutRemainingMs returns 0 when not locked out`() {
        pinManager.setPin("123456")
        assertEquals(0L, pinManager.getLockoutRemainingMs())
    }

    @Test
    fun `successful verification within 24h does NOT reset failed attempts`() {
        pinManager.setPin("123456")
        pinManager.verifyPin("000000")
        pinManager.verifyPin("000000")
        assertEquals(PinManager.MAX_ATTEMPTS - 2, pinManager.getRemainingAttempts())

        // Simulate a quiet attacker scenario: legitimate owner unlocks within
        // the 24h decay window. The counter must NOT reset, so the attacker
        // cannot grind between owner-initiated unlocks.
        fakeTimeMs += 60_000L // 1 minute later
        pinManager.verifyPin("123456")
        assertEquals(
            PinManager.MAX_ATTEMPTS - 2,
            pinManager.getRemainingAttempts()
        )
    }

    @Test
    fun `successful verification after 24h decay resets failed attempts`() {
        pinManager.setPin("123456")
        pinManager.verifyPin("000000")
        pinManager.verifyPin("000000")
        assertEquals(PinManager.MAX_ATTEMPTS - 2, pinManager.getRemainingAttempts())

        // Time-travel past the decay window.
        fakeTimeMs += PinManager.LOCKOUT_DECAY_MS + 1
        pinManager.verifyPin("123456")
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
    }

    @Test
    fun `lockout escalates from 30s to 1 minute on the sixth failure`() {
        pinManager.setPin("123456")
        // 5 failures -> 30 s lockout
        repeat(5) { pinManager.verifyPin("000000") }
        assertEquals(30_000L, pinManager.getLockoutRemainingMs())

        // Wait out the lockout, then fail once more -> 1 minute lockout
        fakeTimeMs += 30_001L
        assertFalse(pinManager.isLockedOut())
        pinManager.verifyPin("000000")
        assertEquals(60_000L, pinManager.getLockoutRemainingMs())
    }

    @Test
    fun `lockout escalates through documented schedule`() {
        pinManager.setPin("123456")
        // 5 failures -> 30s (attempt #5 records the lockout)
        repeat(5) { pinManager.verifyPin("000000") }
        assertEquals(30_000L, pinManager.getLockoutRemainingMs())

        val schedule = listOf(
            60_000L,        // attempt 6 -> 1 min
            300_000L,       // attempt 7 -> 5 min
            1_800_000L,     // attempt 8 -> 30 min
            3_600_000L      // attempt 9 -> 1 h
        )

        for (expectedLockoutMs in schedule) {
            // Wait out the current lockout, then trigger the next failure.
            fakeTimeMs += pinManager.getLockoutRemainingMs() + 1
            pinManager.verifyPin("000000")
            assertEquals(expectedLockoutMs, pinManager.getLockoutRemainingMs())
        }

        // Attempt 10 -> permanent
        fakeTimeMs += pinManager.getLockoutRemainingMs() + 1
        pinManager.verifyPin("000000")
        assertTrue(pinManager.isPermanentlyLocked())
    }

    @Test
    fun `setPin resets failed attempts and lockout`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())

        pinManager.setPin("654321")
        assertFalse(pinManager.isLockedOut())
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
        assertTrue(pinManager.verifyPin("654321"))
    }

    @Test
    fun `lockout state persists across PinManager instances`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val newPinManager = PinManager(context, blake2b)
        newPinManager.testPrefs = pinManager.testPrefs
        newPinManager.timeProvider = { fakeTimeMs }
        newPinManager.argon2Iterations = 1
        newPinManager.argon2MemoryKb = 8
        newPinManager.argon2Parallelism = 1

        assertTrue(newPinManager.isLockedOut())
    }

    // -- removePin backup guard --

    @Test
    fun `removePin throws if backups exist and force is false`() {
        pinManager.setPin("123456")
        pinManager.setBackupChecker { true }

        try {
            pinManager.removePin(force = false)
            fail("Should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("backup"))
        }
    }

    @Test
    fun `removePin succeeds if no backups exist`() {
        pinManager.setPin("123456")
        pinManager.setBackupChecker { false }

        pinManager.removePin(force = false)
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `removePin with force bypasses backup check`() {
        pinManager.setPin("123456")
        pinManager.setBackupChecker { true }

        pinManager.removePin(force = true)
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `removePin without backup checker works normally`() {
        pinManager.setPin("123456")
        // No backup checker set — backupChecker is null
        pinManager.removePin()
        assertFalse(pinManager.hasPin())
    }

    // -- CharArray overloads --

    @Test
    fun `setPinFromChars and verifyPinFromChars work correctly`() {
        pinManager.setPinFromChars("123456".toCharArray())
        assertTrue(pinManager.verifyPinFromChars("123456".toCharArray()))
        assertFalse(pinManager.verifyPinFromChars("654321".toCharArray()))
    }

    @Test
    fun `setPinFromChars is compatible with verifyPin String`() {
        pinManager.setPinFromChars("123456".toCharArray())
        assertTrue(pinManager.verifyPin("123456"))
    }

    @Test
    fun `setPin String is compatible with verifyPinFromChars`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.verifyPinFromChars("123456".toCharArray()))
    }

    // -- KDF migration (Blake2b legacy -> Argon2id) --

    @Test
    fun `legacy Blake2b hash verifies and silently upgrades to Argon2id`() {
        // Simulate a v1.6.x install: write a legacy Blake2b hash directly.
        val prefs = pinManager.testPrefs!!
        val salt = ByteArray(PinManager.SALT_SIZE) { i -> (i + 1).toByte() }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val pinBytes = "123456".toByteArray(Charsets.UTF_8)
        val legacyInput = salt + pinBytes
        val legacyHash = blake2b.hash(legacyInput).joinToString("") { "%02x".format(it) }

        prefs.edit()
            .putString("pin_salt", saltHex)
            .putString("pin_hash", legacyHash)
            .putInt("pin_kdf_version", PinManager.KDF_VERSION_LEGACY_BLAKE2B)
            .apply()

        // First verify uses the legacy path and should succeed.
        assertTrue(pinManager.verifyPin("123456"))

        // The hash should have been silently re-derived as Argon2id.
        assertEquals(
            PinManager.KDF_VERSION_ARGON2ID,
            prefs.getInt("pin_kdf_version", -1)
        )
        // The new hash must differ from the legacy one (different KDF).
        assertNotEquals(legacyHash, prefs.getString("pin_hash", null))
        // Subsequent verifies use the new hash.
        assertTrue(pinManager.verifyPin("123456"))
        assertFalse(pinManager.verifyPin("000000"))
    }

    @Test
    fun `legacy hash with wrong PIN does not trigger migration`() {
        val prefs = pinManager.testPrefs!!
        val salt = ByteArray(PinManager.SALT_SIZE) { i -> (i + 1).toByte() }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val legacyInput = salt + "123456".toByteArray(Charsets.UTF_8)
        val legacyHash = blake2b.hash(legacyInput).joinToString("") { "%02x".format(it) }

        prefs.edit()
            .putString("pin_salt", saltHex)
            .putString("pin_hash", legacyHash)
            .putInt("pin_kdf_version", PinManager.KDF_VERSION_LEGACY_BLAKE2B)
            .apply()

        assertFalse(pinManager.verifyPin("999999"))
        // No migration happened: stored hash + version unchanged.
        assertEquals(legacyHash, prefs.getString("pin_hash", null))
        assertEquals(
            PinManager.KDF_VERSION_LEGACY_BLAKE2B,
            prefs.getInt("pin_kdf_version", -1)
        )
    }

    @Test
    fun `legacy stores without KDF version still verify (assumed Blake2b)`() {
        // Older v1.6.x installs may not have written the version key at all.
        val prefs = pinManager.testPrefs!!
        val salt = ByteArray(PinManager.SALT_SIZE) { i -> (i + 2).toByte() }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val legacyInput = salt + "999999".toByteArray(Charsets.UTF_8)
        val legacyHash = blake2b.hash(legacyInput).joinToString("") { "%02x".format(it) }

        prefs.edit()
            .putString("pin_salt", saltHex)
            .putString("pin_hash", legacyHash)
            // Note: no pin_kdf_version key
            .apply()

        assertTrue(pinManager.verifyPin("999999"))
        // Should have been migrated.
        assertEquals(
            PinManager.KDF_VERSION_ARGON2ID,
            prefs.getInt("pin_kdf_version", -1)
        )
    }
}
