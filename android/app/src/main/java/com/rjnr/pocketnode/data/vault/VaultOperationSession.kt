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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents

/** Only a currently active CryptoObject system handoff can temporarily outlive host ON_STOP. */
internal class VaultOperationSession(private val activity: FragmentActivity, private val parent: VaultOperationSession?) :
    AbstractCoroutineContextElement(Key) {

    private var systemHandoff = false

    suspend fun <T> systemAuthentication(
        mode: VaultDevicePromptMode = VaultDevicePromptMode.BIOMETRIC_ONLY,
        discard: (T) -> Unit = {}, block: suspend () -> T,
    ): T {
        if (mode == VaultDevicePromptMode.CREDENTIAL_ONLY && android.os.Build.VERSION.SDK_INT < 30) {
            throw CancellationException("Unsupported credential handoff")
        }
        // Only a captured credential-only prompt can leave this host for trusted Android UI.
        // Biometric-only prompts never receive the exemption, including on API30+.
        val lineage = generateSequence(this) { it.parent }.toList()
        check(lineage.none { it.systemHandoff }) { "Concurrent credential handoff" }
        lineage.forEach { it.systemHandoff = mode == VaultDevicePromptMode.CREDENTIAL_ONLY }
        class OwnedValue(val value: T)
        var owned: OwnedValue? = null
        try {
            val value = withTimeout(60_000) {
                val result = block()
                owned = OwnedValue(result)
                // Includes callback/ON_START race within the total pending-authentication bound.
                withTimeout(2_000) { awaitForeground() }
                result
            }
            owned = null
            return value
        } finally {
            owned?.let { discard(it.value) }
            lineage.forEach { it.systemHandoff = false }
        }
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

    companion object Key : CoroutineContext.Key<VaultOperationSession> {
        suspend fun <T> run(activity: FragmentActivity, block: suspend (VaultOperationSession) -> T): T = withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw CancellationException("Wallet operation requires foreground")
                val parent = currentCoroutineContext()[Key]
                require(parent == null || parent.activity === activity) { "Wallet operation host changed" }
                val session = VaultOperationSession(activity, parent)
                val job = currentCoroutineContext()[Job]!!
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY || event == Lifecycle.Event.ON_STOP && !session.systemHandoff) {
                        job.cancel(CancellationException("Wallet operation ended"))
                    }
                }
                val lock = ReauthLockEvents.locks.value
                val locks = launch(start = CoroutineStart.UNDISPATCHED) {
                    ReauthLockEvents.locks.collect { if (it != lock) job.cancel(CancellationException("Wallet operation locked")) }
                }
                try {
                    activity.lifecycle.addObserver(observer)
                    withContext(session) { block(session) }
                }
                finally { activity.lifecycle.removeObserver(observer); locks.cancel() }
            }
        }
    }
}
