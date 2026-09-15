package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * Times one [Argon2id] hash at the production PIN parameters and prints the number.
 *
 * There is no timing assertion: a shared unit-test suite runs on a CI machine, a
 * developer laptop and an iOS simulator, and a threshold that is meaningful on one is
 * either flaky or vacuous on the others. The figure this prints on
 * `iosSimulatorArm64` is the input to the on-device budget for #509 (target under
 * 1.5 s on an iPhone), and the maintainer records it in the PR.
 */
class Argon2idBenchmarkTest {

    @Test
    fun `hash at production PIN parameters`() {
        val password = "123456".encodeToByteArray()
        val salt = ByteArray(32) { it.toByte() }
        val params = Argon2id.Params(
            iterations = 3,
            memoryKib = 64 * 1024,
            parallelism = 4,
            tagLength = 32,
        )

        val start = TimeSource.Monotonic.markNow()
        val tag = Argon2id.hash(password, salt, params)
        val elapsed = start.elapsedNow()

        println("Argon2id 64MiB/3/4: ${elapsed.inWholeMilliseconds} ms")
        assertEquals(32, tag.size)
    }
}
