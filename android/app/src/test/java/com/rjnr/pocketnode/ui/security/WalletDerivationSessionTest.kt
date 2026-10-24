package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class WalletDerivationSessionTest {
    @Test fun `stop or reauth cancels suspended derivation and clears owned key`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (stop in listOf(true, false)) {
                val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
                val key = ByteArray(32) { 7 }
                val job = async {
                    WalletDerivationSession.run(host.get(), ReauthLockEvents.locks.value) {
                        try {
                            if (stop) host.pause().stop() else ReauthLockEvents.onLocked()
                            yield()
                        } finally { key.fill(0) }
                    }
                }
                job.join()
                assertTrue(job.isCancelled)
                assertArrayEquals(ByteArray(32), key)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test @Config(sdk = [30]) fun `only actual nested credential handoff delegates writer authority and reauth still cancels`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            var returned = false
            WalletDerivationSession.run(host.get(), ReauthLockEvents.locks.value) { session ->
                session.persist {
                    com.rjnr.pocketnode.data.vault.VaultOperationSession.run(host.get()) { credential ->
                        credential.systemAuthentication(com.rjnr.pocketnode.data.vault.VaultDevicePromptMode.CREDENTIAL_ONLY) {
                            host.pause().stop(); yield(); host.start().resume(); returned = true
                        }
                    }
                }
            }
            assertTrue(returned)
            val job = async {
                WalletDerivationSession.run(host.get(), ReauthLockEvents.locks.value) { session ->
                    session.persist { ReauthLockEvents.onLocked(); yield() }
                }
            }
            job.join(); assertTrue(job.isCancelled)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `a lock during preceding reader authentication refuses derivation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val old = ReauthLockEvents.locks.value; ReauthLockEvents.onLocked()
            var entered = false
            val job = async { WalletDerivationSession.run(host.get(), old) { entered = true } }
            job.join(); assertTrue(job.isCancelled); assertFalse(entered)
        } finally { Dispatchers.resetMain() }
    }    @Test fun `ordinary persist stop has no writer wide exemption`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val key = ByteArray(32) { 7 }; var returned = false
            val job = async {
                WalletDerivationSession.run(host.get(), ReauthLockEvents.locks.value) { session ->
                    try {
                        session.persist { host.pause().stop(); yield(); returned = true }
                    } finally { key.fill(0) }
                }
            }
            job.join(); host.start().resume()
            assertTrue(job.isCancelled); assertFalse(returned); assertArrayEquals(ByteArray(32), key)
        } finally { Dispatchers.resetMain() }
    }


}
