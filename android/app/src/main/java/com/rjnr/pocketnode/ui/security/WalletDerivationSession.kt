package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import kotlinx.coroutines.*

/** Owns transient material between prompts; each nested writer owns its trusted system handoff. */
internal class WalletDerivationSession private constructor(
    private val activity: FragmentActivity,
    private val lock: Int,
    private val job: Job,
) {
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY)
            job.cancel(CancellationException("Wallet operation ended"))
    }
    private fun checkActive() {
        job.ensureActive()
        if (ReauthLockEvents.locks.value != lock || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            throw CancellationException("Wallet operation ended")
    }
    /** The central writer independently observes lifecycle, including its bounded credential handoff. */
    suspend fun <T> persist(block: suspend () -> T): T {
        checkActive()
        activity.lifecycle.removeObserver(observer)
        try { return block() }
        finally { activity.lifecycle.addObserver(observer); checkActive() }
    }
    companion object {
        suspend fun <T> run(activity: FragmentActivity, lock: Int, block: suspend (WalletDerivationSession) -> T): T = withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                val job = currentCoroutineContext()[Job]!!
                val session = WalletDerivationSession(activity, lock, job)
                session.checkActive()
                val locks = launch(start = CoroutineStart.UNDISPATCHED) {
                    ReauthLockEvents.locks.collect { if (it != lock) job.cancel(CancellationException("Wallet operation locked")) }
                }
                activity.lifecycle.addObserver(session.observer)
                try { block(session) }
                finally { activity.lifecycle.removeObserver(session.observer); locks.cancel() }
            }
        }
    }
}
