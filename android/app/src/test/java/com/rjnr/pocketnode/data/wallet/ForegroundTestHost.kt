package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import io.mockk.every
import io.mockk.mockk

/** Real observable lifecycle with a mocked Android host for plain JVM ViewModel tests. */
class ForegroundTestHost {
    val activity = mockk<FragmentActivity>(relaxed = true)
    val lifecycle = LifecycleRegistry.createUnsafe(activity)
    init {
        every { activity.lifecycle } returns lifecycle
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun stopResume() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
}
