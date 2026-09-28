package com.rjnr.pocketnode.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The #524 per-session ViewModel stores behind the lock screen. */
class ReauthSessionStoreTest {

    class Probe : ViewModel() {
        var cleared = false
        public override fun onCleared() {
            cleared = true
        }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
    }

    private fun probeIn(store: ViewModelStore): Probe = ViewModelProvider(store, factory)[Probe::class.java]

    private val sessions = ReauthSessionStore()

    @Test
    fun `asking for a new session's store clears nothing (NIT-2)`() {
        val first = probeIn(sessions.storeFor(1))

        sessions.storeFor(2)

        assertFalse(first.cleared)
        assertSame(first, probeIn(sessions.storeFor(1)))
    }

    @Test
    fun `retainOnly clears every other session`() {
        val first = probeIn(sessions.storeFor(1))
        val second = probeIn(sessions.storeFor(2))

        sessions.retainOnly(2)

        assertTrue(first.cleared)
        assertFalse(second.cleared)
    }

    @Test
    fun `clear and clearAll clear the stores`() {
        val first = probeIn(sessions.storeFor(1))
        val second = probeIn(sessions.storeFor(2))

        sessions.clear(1)
        assertTrue(first.cleared)
        assertFalse(second.cleared)

        sessions.clearAll()
        assertTrue(second.cleared)
    }
}
