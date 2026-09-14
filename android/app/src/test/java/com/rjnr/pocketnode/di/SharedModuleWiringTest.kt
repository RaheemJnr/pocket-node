package com.rjnr.pocketnode.di

import com.rjnr.pocketnode.core.SharedCore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the `:shared` KMP module is on the app's classpath (#451). Replaces a
 * startup log line that R8 would have stripped via -assumenosideeffects.
 */
class SharedModuleWiringTest {
    @Test
    fun sharedCoreIsReachableFromApp() {
        assertTrue(SharedCore.describe().contains(SharedCore.VERSION))
    }
}
