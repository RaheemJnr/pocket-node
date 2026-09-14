package com.rjnr.pocketnode.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SyncServiceCommands] addresses the service by name instead of by class
 * literal, so that `GatewayRepository -> SyncForegroundService ->
 * GatewayRepository` type cycle stays broken (#460). Nothing in the compiler
 * enforces that the name still matches the class, so pin it here — test code
 * may reference both sides without re-creating the cycle in production code.
 */
class SyncServiceCommandsTest {

    @Test
    fun `service class name constant matches the real service class`() {
        assertEquals(
            SyncForegroundService::class.java.name,
            SyncServiceCommands.SERVICE_CLASS_NAME
        )
    }
}
