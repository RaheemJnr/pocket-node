package com.rjnr.pocketnode.data.auth

import com.rjnr.pocketnode.core.crypto.Argon2id
import com.rjnr.pocketnode.core.crypto.Blake2b
import com.rjnr.pocketnode.core.crypto.EntropySource
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.core.log.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lockout schedule, the counter semantics and the KDF-v1 -> v2 migration,
 * exercised against an in-memory [PinStore] and a fake clock.
 *
 * This is the platform-neutral half of what `PinManagerTest` covers on Android;
 * it also runs on `iosSimulatorArm64Test`, which is the point: both platforms
 * must lock a wallet out on exactly the same schedule.
 *
 * Argon2id runs at t=1 / m=8 KiB / p=1 throughout. The parameters do not change
 * any of the behaviour under test and the production cost (64 MiB, ~300 ms) would
 * make the suite unusable.
 */
class PinPolicyTest {

    private val pin = "123456".encodeToByteArray()
    private val wrongPin = "000000".encodeToByteArray()

    private lateinit var store: FakePinStore
    private var now: Long = 1_000_000L

    private fun newPolicy(): PinPolicy = PinPolicy(
        store = store,
        entropy = FixedEntropy,
        clock = { now },
        logger = NoopLogger,
        argon2Params = TEST_ARGON2,
    )

    private fun policyWithFreshStore(): PinPolicy {
        store = FakePinStore()
        return newPolicy()
    }

    // -- set / verify --

    @Test
    fun setPinThenVerifyAcceptsTheSamePin() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        assertTrue(policy.verify(pin))
    }

    @Test
    fun verifyRejectsADifferentPin() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        assertFalse(policy.verify(wrongPin))
    }

    @Test
    fun verifyReturnsFalseWhenNoPinIsSet() {
        val policy = policyWithFreshStore()
        assertFalse(policy.hasPin())
        assertFalse(policy.verify(pin))
        // Nothing to brute-force yet, so nothing is counted against the user
        // and nothing is written at all.
        assertNull(store.attempts)
        assertEquals(0, policy.getRemainingAttempts() - PinPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun setPinStoresArgon2idAndAFreshSalt() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        assertEquals(PinPolicy.KDF_VERSION_ARGON2ID, store.kdf)
        assertEquals(PinPolicy.SALT_SIZE, store.saltBytes?.size)
    }

    @Test
    fun setPinRejectsAnythingThatIsNotSixDigits() {
        val policy = policyWithFreshStore()
        assertFailsWith<IllegalArgumentException> { policy.setPin("12345".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { policy.setPin("1234567".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { policy.setPin("12345a".encodeToByteArray()) }
    }

    // -- lockout schedule --

    @Test
    fun lockoutDurationForCoversTheDocumentedSchedule() {
        val expected = mapOf(
            1 to 0L,
            2 to 0L,
            3 to 0L,
            4 to 0L,
            5 to 30_000L,
            6 to 60_000L,
            7 to 300_000L,
            8 to 1_800_000L,
            9 to 3_600_000L,
            10 to Long.MAX_VALUE,
            11 to Long.MAX_VALUE,
            12 to Long.MAX_VALUE,
        )
        for ((attempts, duration) in expected) {
            assertEquals(duration, PinPolicy.lockoutDurationFor(attempts), "attempts=$attempts")
        }
    }

    @Test
    fun eachFailureMovesTheWalletThroughTheSchedule() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)

        // attempts -> (lockoutMs, remainingAttempts, permanent)
        val table = listOf(
            Triple(1, 0L, 4),
            Triple(2, 0L, 3),
            Triple(3, 0L, 2),
            Triple(4, 0L, 1),
            Triple(5, 30_000L, 0),
            Triple(6, 60_000L, 0),
            Triple(7, 300_000L, 0),
            Triple(8, 1_800_000L, 0),
            Triple(9, 3_600_000L, 0),
        )

        for ((attempt, expectedLockoutMs, expectedRemaining) in table) {
            // Wait out whatever lockout the previous failure started.
            now += policy.getLockoutRemainingMs() + 1
            assertFalse(policy.isLockedOut(), "attempt $attempt should be reachable")

            assertFalse(policy.verify(wrongPin), "attempt $attempt")
            assertEquals(attempt, store.attempts, "attempt $attempt counter")
            assertEquals(now, store.lastFailure, "attempt $attempt stamp")
            assertEquals(expectedLockoutMs, policy.getLockoutRemainingMs(), "attempt $attempt lockout")
            assertEquals(expectedLockoutMs > 0, policy.isLockedOut(), "attempt $attempt locked")
            assertEquals(expectedRemaining, policy.getRemainingAttempts(), "attempt $attempt remaining")
            assertFalse(policy.isPermanentlyLocked(), "attempt $attempt permanent")
        }

        // The tenth failure locks the wallet for good.
        now += policy.getLockoutRemainingMs() + 1
        assertFalse(policy.verify(wrongPin))
        assertEquals(10, store.attempts)
        assertTrue(policy.isPermanentlyLocked())
        assertTrue(policy.isLockedOut())
        assertEquals(Long.MAX_VALUE, store.lockout)
        assertEquals(0, policy.getRemainingAttempts())
    }

    @Test
    fun countersBeyondThePermanentThresholdStayPermanent() {
        // A permanent lockout blocks `verify`, so a counter of 11 or 12 can only
        // come from a store written by an older build. It must still read as
        // permanently locked rather than wrapping back to "you may try again".
        for (attempts in 11..12) {
            val policy = policyWithFreshStore()
            policy.setPin(pin)
            store.update {
                failedAttempts(attempts)
                lockoutUntil(Long.MAX_VALUE)
            }
            assertTrue(policy.isPermanentlyLocked(), "attempts=$attempts")
            assertTrue(policy.isLockedOut(), "attempts=$attempts")
            assertEquals(0, policy.getRemainingAttempts(), "attempts=$attempts")
        }
    }

    @Test
    fun aCorrectPinIsRejectedWhileLockedOut() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(PinPolicy.MAX_ATTEMPTS) { policy.verify(wrongPin) }
        assertTrue(policy.isLockedOut())
        assertFalse(policy.verify(pin))
        // The rejected-while-locked attempt is not counted as a failure.
        assertEquals(PinPolicy.MAX_ATTEMPTS, store.attempts)
    }

    @Test
    fun theLockoutExpiresButTheCounterDoesNot() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(PinPolicy.MAX_ATTEMPTS) { policy.verify(wrongPin) }
        assertTrue(policy.isLockedOut())

        now += PinPolicy.LOCKOUT_DURATION_MS + 1
        assertFalse(policy.isLockedOut())
        assertEquals(0L, policy.getLockoutRemainingMs())
        // Counter untouched, so the next failure escalates to the 1-minute step.
        assertEquals(PinPolicy.MAX_ATTEMPTS, store.attempts)
        assertEquals(0, policy.getRemainingAttempts())

        assertFalse(policy.verify(wrongPin))
        assertEquals(60_000L, policy.getLockoutRemainingMs())
    }

    @Test
    fun aSuccessfulVerifyResetsTheCounterAndClearsTheLockout() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(2) { policy.verify(wrongPin) }
        assertEquals(PinPolicy.MAX_ATTEMPTS - 2, policy.getRemainingAttempts())

        now += 60_000L
        assertTrue(policy.verify(pin))
        assertEquals(PinPolicy.MAX_ATTEMPTS, policy.getRemainingAttempts())
        assertEquals(0, store.attempts)
        assertNull(store.lastFailure)
        assertNull(store.lockout)
    }

    @Test
    fun aSuccessfulVerifyAfterTheOldDecayWindowAlsoResets() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(2) { policy.verify(wrongPin) }

        now += PinPolicy.LOCKOUT_DECAY_MS + 1
        assertTrue(policy.verify(pin))
        assertEquals(PinPolicy.MAX_ATTEMPTS, policy.getRemainingAttempts())
    }

    @Test
    fun setPinClearsTheLockoutAndTheCounter() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(PinPolicy.MAX_ATTEMPTS) { policy.verify(wrongPin) }
        assertTrue(policy.isLockedOut())

        policy.setPin("654321".encodeToByteArray())
        assertFalse(policy.isLockedOut())
        assertEquals(PinPolicy.MAX_ATTEMPTS, policy.getRemainingAttempts())
        assertNull(store.lastFailure)
        assertNull(store.lockout)
        assertTrue(policy.verify("654321".encodeToByteArray()))
        assertFalse(policy.verify(pin))
    }

    @Test
    fun resetFailedAttemptsKeepsThePinButClearsTheFailureState() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(PinPolicy.MAX_ATTEMPTS) { policy.verify(wrongPin) }
        assertTrue(policy.isLockedOut())

        policy.resetFailedAttempts()
        assertFalse(policy.isLockedOut())
        assertEquals(PinPolicy.MAX_ATTEMPTS, policy.getRemainingAttempts())
        assertEquals(0, store.attempts, "the counter is zeroed, not removed, while a PIN is set")
        assertNull(store.lastFailure)
        assertNull(store.lockout)
        assertTrue(policy.hasPin())
        assertTrue(policy.verify(pin))
    }

    @Test
    fun aDurableResetGoesThroughTheDurableWrite() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(PinPolicy.MAX_ATTEMPTS) { policy.verify(wrongPin) }
        val before = store.durableWrites

        policy.resetFailedAttempts(durable = true)
        assertEquals(before + 1, store.durableWrites)
        assertFalse(policy.isLockedOut())
        assertEquals(PinPolicy.MAX_ATTEMPTS, policy.getRemainingAttempts())

        // The default stays on the ordinary write.
        policy.resetFailedAttempts()
        assertEquals(before + 1, store.durableWrites)
    }

    @Test
    fun removePinClearsEveryField() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        policy.verify(wrongPin)

        policy.removePin()
        assertFalse(policy.hasPin())
        assertNull(store.hash)
        assertNull(store.saltBytes)
        assertNull(store.kdf)
        // Removed, not zeroed: "no PIN configured" must not be stored the same
        // way as "a PIN with no failures against it".
        assertNull(store.attempts)
        assertNull(store.lastFailure)
        assertNull(store.lockout)
    }

    @Test
    fun theCounterSurvivesANewPolicyOverTheSameStore() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        repeat(PinPolicy.MAX_ATTEMPTS) { policy.verify(wrongPin) }

        val reopened = newPolicy()
        assertTrue(reopened.isLockedOut())
        assertEquals(0, reopened.getRemainingAttempts())
    }

    // -- KDF versions --

    @Test
    fun anUnknownKdfVersionRefusesToVerify() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        store.update { kdfVersion(99) }

        assertFalse(policy.verify(pin))
        // Refusing is not a failed attempt: the store is broken, not the user.
        assertEquals(0, store.attempts)
        assertNull(store.lockout)
    }

    @Test
    fun aLegacyBlake2bHashVerifiesAndMigratesToArgon2id() {
        store = FakePinStore()
        val salt = ByteArray(PinPolicy.SALT_SIZE) { (it + 1).toByte() }
        val legacyHash = Blake2b.digest(salt + pin).toHexStringNoPrefix()
        store.update {
            salt(salt)
            pinHash(legacyHash)
            kdfVersion(PinPolicy.KDF_VERSION_LEGACY_BLAKE2B)
        }
        val policy = newPolicy()

        assertTrue(policy.verify(pin))
        assertEquals(PinPolicy.KDF_VERSION_ARGON2ID, store.kdf)
        assertNotEquals(legacyHash, store.hash)
        // The salt is reused, so the migrated hash is over the same inputs.
        assertEquals(salt.toHexStringNoPrefix(), store.saltBytes?.toHexStringNoPrefix())

        // The next verify goes through the Argon2id branch.
        assertTrue(policy.verify(pin))
        assertFalse(policy.verify(wrongPin))
    }

    @Test
    fun aLegacyStoreWithNoKdfVersionIsTreatedAsBlake2b() {
        store = FakePinStore()
        val salt = ByteArray(PinPolicy.SALT_SIZE) { (it + 2).toByte() }
        store.update {
            salt(salt)
            pinHash(Blake2b.digest(salt + pin).toHexStringNoPrefix())
        }
        val policy = newPolicy()

        assertNull(store.kdf)
        assertTrue(policy.verify(pin))
        assertEquals(PinPolicy.KDF_VERSION_ARGON2ID, store.kdf)
    }

    @Test
    fun aWrongPinAgainstALegacyHashDoesNotMigrate() {
        store = FakePinStore()
        val salt = ByteArray(PinPolicy.SALT_SIZE) { (it + 1).toByte() }
        val legacyHash = Blake2b.digest(salt + pin).toHexStringNoPrefix()
        store.update {
            salt(salt)
            pinHash(legacyHash)
            kdfVersion(PinPolicy.KDF_VERSION_LEGACY_BLAKE2B)
        }
        val policy = newPolicy()

        assertFalse(policy.verify(wrongPin))
        assertEquals(legacyHash, store.hash)
        assertEquals(PinPolicy.KDF_VERSION_LEGACY_BLAKE2B, store.kdf)
        assertEquals(1, store.attempts)
    }

    @Test
    fun aFailedMigrationWriteStillAcceptsThePin() {
        store = FakePinStore()
        val salt = ByteArray(PinPolicy.SALT_SIZE) { (it + 3).toByte() }
        val legacyHash = Blake2b.digest(salt + pin).toHexStringNoPrefix()
        store.update {
            salt(salt)
            pinHash(legacyHash)
            kdfVersion(PinPolicy.KDF_VERSION_LEGACY_BLAKE2B)
        }
        val policy = newPolicy()

        // The migration write is the next one; make it blow up.
        store.throwOnNextUpdate = true
        assertTrue(policy.verify(pin), "a storage failure must not reject a correct PIN")

        // Nothing was migrated, and the legacy hash still verifies next time.
        assertEquals(legacyHash, store.hash)
        assertEquals(PinPolicy.KDF_VERSION_LEGACY_BLAKE2B, store.kdf)
        assertTrue(policy.verify(pin))
        assertEquals(PinPolicy.KDF_VERSION_ARGON2ID, store.kdf)
    }

    @Test
    fun aCorrectPinIsAcceptedEvenIfTheCounterResetCannotBeWritten() {
        val policy = policyWithFreshStore()
        policy.setPin(pin)
        assertFalse(policy.verify(wrongPin))
        assertEquals(1, store.attempts)

        // The next write is the post-success counter reset. Losing it must not
        // cost the user access to their own wallet.
        store.throwOnNextUpdate = true
        assertTrue(policy.verify(pin))

        // The reset did not land, so the counter is still where it was and the
        // next success retries it.
        assertEquals(1, store.attempts)
        assertTrue(policy.verify(pin))
        assertEquals(0, store.attempts)
    }

    private object FixedEntropy : EntropySource {
        // Deterministic, so a hash computed in one test is reproducible in the
        // next. Never do this outside a test.
        override fun nextBytes(n: Int): ByteArray = ByteArray(n) { (it * 7 + 11).toByte() }
    }

    /** In-memory [PinStore] that can be made to fail one write. */
    private class FakePinStore : PinStore {
        // Named apart from the interface getters: `var pinHash` would generate a
        // `getPinHash()` that clashes with the override on the JVM.
        var hash: String? = null
        var saltBytes: ByteArray? = null
        var kdf: Int? = null
        var attempts: Int? = null
        var lastFailure: Long? = null
        var lockout: Long? = null

        /** Consumed by the next write, which throws instead of storing anything. */
        var throwOnNextUpdate: Boolean = false

        /** Counts the writes that asked to be durable. */
        var durableWrites: Int = 0

        override fun getPinHash(): String? = hash
        override fun getSalt(): ByteArray? = saltBytes
        override fun getKdfVersion(): Int? = kdf
        override fun getFailedAttempts(): Int = attempts ?: 0
        override fun getLastFailedAt(): Long? = lastFailure
        override fun getLockoutUntil(): Long? = lockout

        override fun updateDurable(block: PinStore.Editor.() -> Unit) {
            durableWrites++
            update(block)
        }

        override fun update(block: PinStore.Editor.() -> Unit) {
            if (throwOnNextUpdate) {
                throwOnNextUpdate = false
                throw IllegalStateException("simulated storage failure")
            }
            // Staged, then applied in one go, like a real atomic write: a block
            // that throws half way must leave nothing behind.
            var newHash = hash
            var newSalt = saltBytes
            var newKdf = kdf
            var newAttempts = attempts
            var newLastFailure = lastFailure
            var newLockout = lockout
            object : PinStore.Editor {
                override fun pinHash(v: String?) { newHash = v }
                override fun salt(v: ByteArray?) { newSalt = v }
                override fun kdfVersion(v: Int?) { newKdf = v }
                override fun failedAttempts(v: Int?) { newAttempts = v }
                override fun lastFailedAt(v: Long?) { newLastFailure = v }
                override fun lockoutUntil(v: Long?) { newLockout = v }
            }.block()
            hash = newHash
            saltBytes = newSalt
            kdf = newKdf
            attempts = newAttempts
            lastFailure = newLastFailure
            lockout = newLockout
        }
    }

    private companion object {
        val TEST_ARGON2 = Argon2id.Params(
            iterations = 1,
            memoryKib = 8,
            parallelism = 1,
            tagLength = PinPolicy.HASH_OUTPUT_BYTES,
        )
    }
}
