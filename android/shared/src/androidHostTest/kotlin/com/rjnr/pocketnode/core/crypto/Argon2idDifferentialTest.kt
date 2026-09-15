package com.rjnr.pocketnode.core.crypto

import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

        /** Roughly one case in four also carries a secret and associated data. */
        const val EXTRA_INPUT_ODDS = 4

        const val KIB_64_MIB = 64 * 1024
        const val KIB_16_MIB = 16 * 1024

        /** The parameters `PinManager` actually ships with. */
        const val PIN_ITERATIONS = 3
        const val PIN_MEMORY_KIB = 64 * 1024
        const val PIN_PARALLELISM = 4
        const val PIN_TAG_LENGTH = 32
        const val PIN_SALT_LENGTH = 32
    }

    private class Case(
        val index: Int,
        val password: ByteArray,
        val salt: ByteArray,
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val tagLength: Int,
        val secret: ByteArray? = null,
        val associatedData: ByteArray? = null,
    ) {
        /** Parameters only. The password never goes into a log line. */
        fun describe(): String =
            "case $index: t=$iterations m=${memoryKib}KiB p=$parallelism tag=$tagLength " +
                "passwordLen=${password.size} saltLen=${salt.size} " +
                "secretLen=${secret?.size ?: 0} adLen=${associatedData?.size ?: 0}"
    }

    /**
     * The production tuple, spelled out rather than sampled.
     *
     * The random sweep below covers this corner too, but only by luck of the seed. A
     * regression at exactly (t=3, m=64 MiB, p=4, 32-byte salt, 32-byte tag) locks every
     * existing user out of their wallet, so it gets its own case and its own real
     * six-digit PINs.
     */
    @Test
    fun `matches BouncyCastle at the production PIN parameters`() {
        val salt = ByteArray(PIN_SALT_LENGTH) { (it * 7 + 3).toByte() }

        for (pin in listOf("000000", "123456", "987654")) {
            val case = Case(
                index = -1,
                password = pin.toByteArray(Charsets.UTF_8),
                salt = salt,
                iterations = PIN_ITERATIONS,
                memoryKib = PIN_MEMORY_KIB,
                parallelism = PIN_PARALLELISM,
                tagLength = PIN_TAG_LENGTH,
            )

            assertEquals(
                bouncyCastle(case).toHexStringNoPrefix(),
                ours(case).toHexStringNoPrefix(),
                "production PIN parameters, pin length ${pin.length}",
            )
        }
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
        // The secret and associated-data inputs feed H0 and nothing else, but they are
        // the two arguments with no coverage at all from the PIN call site.
        assertTrue(
            cases.count { it.secret != null } >= CASES / EXTRA_INPUT_ODDS / 2,
            "cases carrying a secret and associated data",
        )
        // Every parallelism must appear, including at the small-memory end.
        assertEquals(
            setOf(1, 2, 3, 4),
            cases.map { it.parallelism }.toSet(),
            "parallelism coverage",
        )

        for (case in cases) {
            assertEquals(
                bouncyCastle(case).toHexStringNoPrefix(),
                ours(case).toHexStringNoPrefix(),
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
        // Parallelism is drawn after the memory cost and bounded by it. Raising the
        // memory to fit the lanes instead would push every small case up to 32 KiB and
        // leave the 8-to-31 KiB region tested at p=1 only.
        val maxParallelism = minOf(4, memoryKib / 8)
        val withExtraInputs = random.nextInt(EXTRA_INPUT_ODDS) == 0
        return Case(
            index = index,
            password = random.nextBytes(random.nextInt(0, 65)),
            salt = random.nextBytes(random.nextInt(8, 33)),
            iterations = random.nextInt(1, 4),
            memoryKib = memoryKib,
            parallelism = random.nextInt(1, maxParallelism + 1),
            tagLength = random.nextInt(16, 65),
            secret = if (withExtraInputs) random.nextBytes(random.nextInt(1, 33)) else null,
            associatedData = if (withExtraInputs) random.nextBytes(random.nextInt(1, 33)) else null,
        )
    }

    private fun ours(case: Case): ByteArray = Argon2id.hash(
        password = case.password,
        salt = case.salt,
        params = Argon2id.Params(
            iterations = case.iterations,
            memoryKib = case.memoryKib,
            parallelism = case.parallelism,
            tagLength = case.tagLength,
        ),
        secret = case.secret,
        associatedData = case.associatedData,
    )

    private fun bouncyCastle(case: Case): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(case.iterations)
            .withMemoryAsKB(case.memoryKib)
            .withParallelism(case.parallelism)
            .withSalt(case.salt)
        case.secret?.let { builder.withSecret(it) }
        // `withAdditional`, not `withAdditionalData`: BouncyCastle 1.70's spelling.
        case.associatedData?.let { builder.withAdditional(it) }

        val generator = Argon2BytesGenerator().also { it.init(builder.build()) }
        val out = ByteArray(case.tagLength)
        generator.generateBytes(case.password, out)
        return out
    }
}
