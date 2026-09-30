package com.rjnr.pocketnode.ui.screens.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide signal that the re-auth lock started a new session (#524).
 *
 * The lock keeps the screen underneath (its ViewModels survive), so a
 * ViewModel that holds a revealed or typed secret (a seed phrase shown after
 * its own PIN gate, a recovery phrase or private key being typed) listens
 * here and drops it: a secret must never come back after a lock.
 */
object ReauthLockEvents {
    private val _locks = MutableStateFlow(0)
    val locks: StateFlow<Int> = _locks.asStateFlow()

    /** Called by MainActivity whenever a new lock session starts. */
    fun onLocked() {
        _locks.update { it + 1 }
    }
}

/** Run [onLock] on every re-auth lock that starts after this call. */
fun CoroutineScope.onEachReauthLock(onLock: () -> Unit): Job {
    val seen = ReauthLockEvents.locks.value
    return launch {
        ReauthLockEvents.locks.collect { if (it != seen) onLock() }
    }
}
