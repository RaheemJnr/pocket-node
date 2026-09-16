package com.rjnr.pocketnode.data.sync

import javax.inject.Inject

/**
 * Android [LifecycleProvider]: the one piece of the broadcast watchdog that
 * could not move to `commonMain` (M3 #5), because it reads
 * [androidx.lifecycle.ProcessLifecycleOwner].
 */
class ProcessLifecycleProvider @Inject constructor() : LifecycleProvider {
    override fun isAtLeastStarted(): Boolean =
        androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
}
