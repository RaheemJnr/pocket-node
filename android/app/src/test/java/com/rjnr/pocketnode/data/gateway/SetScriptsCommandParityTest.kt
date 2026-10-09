package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `commonMain` cannot see [LightClientNative], so [SyncCoordinator] carries its
 * own copies of the two `setScripts` command codes while the app's own call
 * sites still pass the JNI object's constants. Both sets reach the same
 * `setScripts` bridge, and a silent divergence would be invisible: passing ALL
 * where PARTIAL was meant replaces the whole registered script set instead of
 * merging into it, unregistering every other wallet and every discovery
 * candidate.
 *
 * Reading the constants does not load the native library, so this runs on the
 * JVM without Robolectric.
 */
class SetScriptsCommandParityTest {

    @Test
    fun `shared ALL matches the JNI constant`() {
        assertEquals(LightClientNative.CMD_SET_SCRIPTS_ALL, SyncCoordinator.CMD_SET_SCRIPTS_ALL)
    }

    @Test
    fun `shared PARTIAL matches the JNI constant`() {
        assertEquals(
            LightClientNative.CMD_SET_SCRIPTS_PARTIAL,
            SyncCoordinator.CMD_SET_SCRIPTS_PARTIAL,
        )
    }
}
