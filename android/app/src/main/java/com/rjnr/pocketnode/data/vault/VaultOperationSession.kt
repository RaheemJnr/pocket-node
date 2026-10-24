package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Only a currently active CryptoObject system handoff can temporarily outlive host ON_STOP. */
internal class VaultOperationSession(private val activity: FragmentActivity) {
    private var systemHandoff = false

    suspend fun <T> systemAuthentication(discard: (T) -> Unit = {}, block: suspend () -> T): T {
        systemHandoff = true
        try {
            val value = block()
            try {
                // The system credential screen may deliver its result just before host ON_START.
                // A Home/background path has only this bounded grace, never an unlocked session.
                withTimeout(2_000) { awaitForeground() }
                return value
            } catch (error: Throwable) { discard(value); throw error }
        } finally { systemHandoff = false }
    }

    private suspend fun awaitForeground() {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) throw CancellationException("Wallet operation ended")
        var observer: LifecycleEventObserver? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START && continuation.isActive) continuation.resume(Unit)
                    if (event == Lifecycle.Event.ON_DESTROY) continuation.cancel()
                }
                activity.lifecycle.addObserver(observer)
            }
        } finally { observer?.let { activity.lifecycle.removeObserver(it) } }
    }

    companion object {
        suspend fun <T> run(activity: FragmentActivity, block: suspend (VaultOperationSession) -> T): T = withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw CancellationException("Wallet operation requires foreground")
                val session = VaultOperationSession(activity)
                val job = currentCoroutineContext()[Job]!!
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY || event == Lifecycle.Event.ON_STOP && !session.systemHandoff) {
                        job.cancel(CancellationException("Wallet operation ended"))
                    }
                }
                activity.lifecycle.addObserver(observer)
                try { block(session) } finally { activity.lifecycle.removeObserver(observer) }
            }
        }
    }
}
