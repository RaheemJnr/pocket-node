package com.rjnr.pocketnode.data.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class VerificationEpochTest {
    @Test fun validGenerationIsStableAcrossReads() {
        val generation = "a".repeat(32)
        assertEquals(generation, VerificationEpoch.requireGeneration("""{"result":{"version":1,"generation":"$generation"}}"""))
    }
    @Test fun absentMalformedOrUnsupportedEpochFailsClosed() {
        for (envelope in listOf(null, "", "{}", "{\"error\":{}}", """{"result":{"version":2,"generation":"${"a".repeat(32)}"}}""", """{"result":{"version":1,"generation":"bad"}}""")) {
            assertFails { VerificationEpoch.requireGeneration(envelope) }
        }
    }
    @Test fun primitiveTypesMustMatchTheNativeContract() {
        assertFails { VerificationEpoch.requireGeneration("""{"result":{"version":"1","generation":"${"a".repeat(32)}"}}""") }
        assertFails { VerificationEpoch.requireGeneration("""{"result":{"version":1,"generation":11111111111111111111111111111111}}""") }
    }

}
