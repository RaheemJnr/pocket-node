package com.rjnr.pocketnode.data.auth

import com.rjnr.pocketnode.core.crypto.hexToByteArray
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.wallet.WalletStorageBoundary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PinChangeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("pin_change", Context.MODE_PRIVATE)
    private val manager = PinManager(context, Blake2b(), NoopLogger).apply {
        testPrefs = prefs
        argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
        setPin("123456")
    }
    private val auth = AuthManager(context).apply { setSessionPin("123456".toCharArray()) }
    private fun authorize(pin: String = "123456") = manager.authorizeChange(pin.toCharArray(), auth, auth.sessionGeneration())

    @Test fun `authenticated change replaces verifier and requires fresh unlock`() {
        val generation = auth.sessionGeneration()
        val authorization = authorize()
        assertNotNull("Fresh current PIN must authorize change", authorization)
        assertEquals(PinManager.ChangeResult.CHANGED, manager.changePin(authorization!!, "654321".toCharArray()))
        assertFalse(auth.hasSessionPin())
        assertFalse(auth.setSessionPinIfCurrent("123456".toCharArray(), generation))
        assertTrue(manager.verifyPin("654321"))
        assertFalse(manager.verifyPin("123456"))
        assertEquals(PinManager.ChangeResult.REJECTED, manager.changePin(authorization, "111111".toCharArray()))
        assertThrows(IllegalStateException::class.java) { manager.setPin("111111") }
        assertThrows(IllegalStateException::class.java) { manager.setPinFromChars("111111".toCharArray()) }
    }

    @Test fun `wrong current PIN or stale generation cannot authorize change`() {
        assertNull(authorize("654321"))
        assertEquals(4, manager.getRemainingAttempts())
        val staleGeneration = auth.sessionGeneration()
        auth.clearSession()
        assertNull(manager.authorizeChange("123456".toCharArray(), auth, staleGeneration))
        assertTrue(manager.verifyPin("123456"))
    }

    @Test fun `revoked authorization cannot mutate PIN after a new unlock`() {
        val authorization = authorize()!!
        auth.clearSession()
        auth.setSessionPin("123456".toCharArray())
        assertEquals(PinManager.ChangeResult.REJECTED, manager.changePin(authorization, "654321".toCharArray()))
        assertTrue(manager.verifyPin("123456"))
    }

    @Test fun `reset fences authorized replacement and verification migration`() {
        val authorization = authorize()!!
        WalletStorageBoundary(context).beginReset()
        assertThrows(IllegalStateException::class.java) { manager.changePin(authorization, "654321".toCharArray()) }
        assertThrows(IllegalStateException::class.java) { manager.verifyPin("123456") }
        assertEquals(authorization.hash, prefs.getString("pin_hash", null))
    }

    @Test fun `failed durable replacement restores old PIN without reopening session`() {
        val authorization = authorize()!!
        var commits = 0
        manager.testPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean { delegate.commit(); return ++commits != 1 }
                }
            }
        }
        assertEquals(PinManager.ChangeResult.FAILED_RESTORED, manager.changePin(authorization, "654321".toCharArray()))
        assertFalse(auth.hasSessionPin())
        assertTrue(manager.verifyPin("123456"))
        assertFalse(manager.verifyPin("654321"))
    }

    @Test fun `uncertain persistence blocks verification until a fresh process`() {
        val authorization = authorize()!!
        manager.testPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean { delegate.commit(); return false }
                }
            }
        }
        assertEquals(PinManager.ChangeResult.FAILED_UNCERTAIN, manager.changePin(authorization, "654321".toCharArray()))
        assertFalse(auth.hasSessionPin())
        assertFalse(manager.verifyPin("123456"))
        assertFalse(manager.verifyPin("654321"))
        assertNull(authorize())
        assertTrue(manager.hasPin())
    }
    @Test fun `lock while replacement hash is staged prevents mutation without waiting`() {
        val authorization = authorize()!!
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        io.mockk.mockkObject(com.rjnr.pocketnode.core.crypto.Argon2id)
        io.mockk.every { com.rjnr.pocketnode.core.crypto.Argon2id.hash(any(), any(), any(), any(), any()) } answers {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            callOriginal()
        }
        try {
            val change = pool.submit(java.util.concurrent.Callable { manager.changePin(authorization, "654321".toCharArray()) })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            pool.submit { auth.clearSession() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            assertEquals(PinManager.ChangeResult.REJECTED, change.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown(); pool.shutdownNow()
            io.mockk.unmockkObject(com.rjnr.pocketnode.core.crypto.Argon2id)
        }
        assertEquals(authorization.hash, prefs.getString("pin_hash", null))
        assertFalse(auth.hasSessionPin())
        assertTrue(manager.verifyPin("123456"))
    }

    @Test fun `lock after claim does not wait for persistence or reopen old session`() {
        val authorization = authorize()!!
        val generation = auth.sessionGeneration()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        manager.testPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return delegate.commit()
                    }
                }
            }
        }
        try {
            val change = pool.submit(java.util.concurrent.Callable { manager.changePin(authorization, "654321".toCharArray()) })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(auth.hasSessionPin())
            pool.submit { auth.clearSession() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            val stale = pool.submit(java.util.concurrent.Callable { auth.setSessionPinIfCurrent("123456".toCharArray(), generation) })
            val verify = pool.submit(java.util.concurrent.Callable { manager.verifyPin("123456") })
            release.countDown()
            assertEquals(PinManager.ChangeResult.CHANGED, change.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(stale.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(verify.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
        assertFalse(auth.hasSessionPin())
        assertTrue(manager.verifyPin("654321"))
    }

    @Test fun `legacy verifier migration is serialized before replacement`() {
        val salt = prefs.getString("pin_salt", null)!!.hexToByteArray()
        val oldHash = com.rjnr.pocketnode.core.crypto.Blake2b().update(salt).update("123456".toByteArray()).doFinal()
        prefs.edit().putInt("pin_kdf_version", 1).putString("pin_hash", oldHash.joinToString("") { "%02x".format(it) }).commit()
        val authorization = authorize()!!
        assertEquals(2, prefs.getInt("pin_kdf_version", 0))
        assertEquals(PinManager.ChangeResult.CHANGED, manager.changePin(authorization, "654321".toCharArray()))
        assertTrue(manager.verifyPin("654321")); assertFalse(manager.verifyPin("123456"))
    }

    @Test fun `PIN change preserves vault profile envelopes and legacy backup bytes`() = kotlinx.coroutines.test.runTest {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.rjnr.pocketnode.data.database.AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val directory = java.io.File(context.filesDir, "key_backups").apply { mkdirs() }
        val copies = listOf("synthetic.enc", "synthetic.enc.tmp").map { java.io.File(directory, it).apply { writeBytes(byteArrayOf(3, 9, 1)) } }
        try {
            database.walletVaultDao().insert(com.rjnr.pocketnode.data.database.entity.WalletVaultEntity("synthetic", 1, 1, byteArrayOf(7, 8, 9), false))
            database.walletVaultDao().insertProfile(com.rjnr.pocketnode.data.database.entity.WalletPassphraseProfileEntity(envelope = byteArrayOf(4, 5, 6)))
            manager.setBackupChecker { throw AssertionError("PIN change must not rewrite backups") }
            assertEquals(PinManager.ChangeResult.CHANGED, manager.changePin(authorize()!!, "654321".toCharArray()))
            assertArrayEquals(byteArrayOf(7, 8, 9), database.walletVaultDao().get("synthetic")!!.envelope)
            assertArrayEquals(byteArrayOf(4, 5, 6), database.walletVaultDao().profile()!!.envelope)
            copies.forEach { assertArrayEquals(byteArrayOf(3, 9, 1), it.readBytes()) }
        } finally { copies.forEach { it.delete() }; database.close() }
    }

    @Test fun `reset during staging wins before the replacement claim`() {
        val authorization = authorize()!!
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        io.mockk.mockkObject(com.rjnr.pocketnode.core.crypto.Argon2id)
        io.mockk.every { com.rjnr.pocketnode.core.crypto.Argon2id.hash(any(), any(), any(), any(), any()) } answers {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); callOriginal()
        }
        try {
            val change = pool.submit(java.util.concurrent.Callable {
                runCatching { manager.changePin(authorization, "654321".toCharArray()) }.exceptionOrNull()
            })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            pool.submit { WalletStorageBoundary(context).beginReset() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            assertTrue(change.get(5, java.util.concurrent.TimeUnit.SECONDS) is IllegalStateException)
            assertEquals(authorization.hash, prefs.getString("pin_hash", null))
        } finally {
            release.countDown(); pool.shutdownNow()
            io.mockk.unmockkObject(com.rjnr.pocketnode.core.crypto.Argon2id)
        }
    }

    @Test fun `fresh current PIN establishes change authorization after biometric dashboard unlock`() {
        // Dashboard biometrics intentionally do not retain a PIN in AuthManager.
        auth.clearSession()
        val generation = auth.sessionGeneration()
        assertFalse(auth.hasSessionPin())
        val authorization = manager.authorizeChange("123456".toCharArray(), auth, generation)
        assertNotNull("Fresh PIN verification must support biometric-unlocked users", authorization)
        assertTrue(auth.hasSessionPin())
        assertEquals(PinManager.ChangeResult.CHANGED, manager.changePin(authorization!!, "654321".toCharArray()))
        assertFalse(auth.hasSessionPin())
        assertTrue(manager.verifyPin("654321"))
    }

    @Test fun `lock during current PIN verification cannot publish a session`() {
        auth.clearSession()
        val generation = auth.sessionGeneration()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        io.mockk.mockkObject(com.rjnr.pocketnode.core.crypto.Argon2id)
        io.mockk.every { com.rjnr.pocketnode.core.crypto.Argon2id.hash(any(), any(), any(), any(), any()) } answers {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); callOriginal()
        }
        try {
            val proof = pool.submit(java.util.concurrent.Callable { manager.authorizeChange("123456".toCharArray(), auth, generation) })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // MainActivity reads hasPin before revoking onStop; this read must not wait for KDF.
            assertTrue(pool.submit(java.util.concurrent.Callable { manager.hasPin() }).get(2, java.util.concurrent.TimeUnit.SECONDS))
            pool.submit { auth.clearSession() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            assertNull(proof.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(auth.hasSessionPin())
        } finally {
            release.countDown(); pool.shutdownNow()
            io.mockk.unmockkObject(com.rjnr.pocketnode.core.crypto.Argon2id)
        }
    }

    @Test fun `reset between current verification and publication blocks session`() = verificationHandoff(reset = true)
    @Test fun `replacement between current verification and publication blocks old session`() = verificationHandoff(reset = false)

    private fun verificationHandoff(reset: Boolean) {
        val session = io.mockk.spyk(auth)
        val earlierAuthorization = manager.authorizeChange("123456".toCharArray(), session, session.sessionGeneration())!!
        val generation = session.sessionGeneration()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        // Pause exactly after real verifier work, before the real fenced publication CAS.
        io.mockk.every { session.setSessionPinAndGetGenerationIfCurrent(any(), any(), any()) } answers {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); callOriginal()
        }
        try {
            val proof = pool.submit(java.util.concurrent.Callable {
                runCatching { manager.authorizeChange("123456".toCharArray(), session, generation) }
            })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if (reset) {
                session.clearSession()
                WalletStorageBoundary(context).beginReset()
            } else {
                assertEquals(PinManager.ChangeResult.CHANGED, manager.changePin(earlierAuthorization, "654321".toCharArray()))
            }
            release.countDown()
            val result = proof.get(5, java.util.concurrent.TimeUnit.SECONDS)
            if (reset) assertTrue(result.exceptionOrNull() is IllegalStateException)
            else { assertTrue(result.isSuccess); assertNull(result.getOrNull()) }
            assertFalse(session.hasSessionPin())
            if (!reset) assertTrue(manager.verifyPin("654321"))
        } finally { release.countDown(); pool.shutdownNow() }
    }

}
