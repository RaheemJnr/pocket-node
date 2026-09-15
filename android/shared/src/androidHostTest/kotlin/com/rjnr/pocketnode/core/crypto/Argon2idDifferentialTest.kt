package com.rjnr.pocketnode.core.crypto

import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Differential test: shared [Argon2id] against BouncyCastle's `Argon2BytesGenerator`,
 * the JVM-only implementation it replaced (#509).
 *
 * BouncyCastle is a TEST-ONLY dependency of this source set. It is what the Android
 * `PinManager` used before this change, so agreement here is what guarantees that an
 * existing PIN still verifies after the upgrade, and that an iOS PIN hash matches an
 * Android one byte for byte.
 *
 * The cases are drawn from a fixed seed, so a failure reproduces from the printed case
 * index. Assertion messages carry the parameters and the index, never the tag or the
 * password: a PIN-derived tag is credential material.
 */
class Argon2idDifferentialTest {

    private companion object {
        const val SEED = 20260915L

        /** Total random parameter sets compared. */
        const val CASES = 200

        /** Of those, how many run at exactly 64 MiB, the production memory cost. */
        const val CASES_AT_64_MIB = 5

        /** Of those, how many run somewhere in [16 MiB, 64 MiB). */
        const val CASES_AT_16_TO_64_MIB = 20

        const val KIB_64_MIB = 64 * 1024
        const val KIB_16_MIB = 16 * 1024
    }

    private data class Case(
        val index: Int,
        val password: ByteArray,
        val salt: ByteArray,
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val tagLength: Int,
    ) {
        /** Parameters only. The password never goes into a log line. */
        fun describe(): String =
            "case $index: t=$iterations m=${memoryKib}KiB p=$parallelism " +
                "tag=$tagLength passwordLen=${password.size} saltLen=${salt.size}"
    }

    @Test
    fun `matches BouncyCastle over 200 random parameter sets`() {
        val random = Random(SEED)
        val cases = buildCases(random)

        // The distribution is part of the contract: the expensive end of the parameter
        // space is where a 32-bit overflow or an indexing mistake would show up, and it
        // is where the production PIN parameters live.
        assertEquals(CASES, cases.size, "case count")
        assertEquals(
            CASES_AT_64_MIB,
            cases.count { it.memoryKib == KIB_64_MIB },
            "cases at 64 MiB",
        )
        assertEquals(
            CASES_AT_64_MIB + CASES_AT_16_TO_64_MIB,
            cases.count { it.memoryKib >= KIB_16_MIB },
            "cases at 16 MiB or more",
        )

        for (case in cases) {
            val ours = Argon2id.hash(
                password = case.password,
                salt = case.salt,
                params = Argon2id.Params(
                    iterations = case.iterations,
                    memoryKib = case.memoryKib,
                    parallelism = case.parallelism,
                    tagLength = case.tagLength,
                ),
            )
            val reference = bouncyCastle(case)

            assertEquals(
                reference.toHexStringNoPrefix(),
                ours.toHexStringNoPrefix(),
                case.describe(),
            )
        }
    }

    /**
     * Builds the case list: the heavy cases first so a memory-related failure surfaces
     * early, then the log-uniform bulk.
     */
    private fun buildCases(random: Random): List<Case> {
        val cases = mutableListOf<Case>()
        var index = 0

        repeat(CASES_AT_64_MIB) {
            cases += randomCase(random, index++, KIB_64_MIB)
        }
        repeat(CASES_AT_16_TO_64_MIB) {
            // Log-uniform inside [16 MiB, 64 MiB).
            val exponent = 14.0 + random.nextDouble() * 2.0
            val kib = 2.0.pow(exponent).toInt().coerceIn(KIB_16_MIB, KIB_64_MIB - 1)
            cases += randomCase(random, index++, kib)
        }
        while (cases.size < CASES) {
            // Log-uniform inside [8 KiB, 16 MiB).
            val exponent = 3.0 + random.nextDouble() * 11.0
            val kib = 2.0.pow(exponent).toInt().coerceIn(8, KIB_16_MIB - 1)
            cases += randomCase(random, index++, kib)
        }
        return cases
    }

    private fun randomCase(random: Random, index: Int, memoryKib: Int): Case {
        val parallelism = random.nextInt(1, 5)
        return Case(
            index = index,
            password = random.nextBytes(random.nextInt(0, 65)),
            salt = random.nextBytes(random.nextInt(8, 33)),
            iterations = random.nextInt(1, 4),
            // Argon2 needs 8 KiB per lane; the small end of the range can undershoot that.
            memoryKib = maxOf(memoryKib, 8 * parallelism),
            parallelism = parallelism,
            tagLength = random.nextInt(16, 65),
        )
    }

    private fun bouncyCastle(case: Case): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(case.iterations)
            .withMemoryAsKB(case.memoryKib)
            .withParallelism(case.parallelism)
            .withSalt(case.salt)
            .build()
        val generator = Argon2BytesGenerator().also { it.init(params) }
        val out = ByteArray(case.tagLength)
        generator.generateBytes(case.password, out)
        return out
    }
}
