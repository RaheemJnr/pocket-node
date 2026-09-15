package com.rjnr.pocketnode.core.crypto

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential tests: shared [Bip39] against `cash.z.ecc.android:kotlin-bip39`,
 * the JVM-only library it replaced (#507).
 *
 * The library is a TEST-ONLY dependency of this source set. It publishes JVM
 * artifacts only, which is exactly why iOS forced the reimplementation, and it
 * must never reappear on a shipping classpath. A disagreement here is a wallet
 * that derives the wrong keys, not a test bug: do not relax an assertion to
 * make it pass.
 *
 * The random inputs are seeded, so a failure reproduces from the printed
 * iteration index. Assertion messages carry that index and nothing else —
 * mnemonics and seeds are key material and must never reach CI logs or JUnit
 * XML. See [fingerprint].
 *
 * Passphrases are ASCII on purpose. [Bip39] skips NFKD (Kotlin/Native has no
 * normaliser), so agreement is only claimed where NFKD is the identity. That
 * limitation is documented on [Bip39] itself.
 */
class Bip39DifferentialTest {

    private companion object {
        const val SEED = 20260915L

        /** Entropy samples compared word-for-word and seed-for-seed. */
        const val ITERATIONS = 2_000

        /** Of those, how many are re-run under a random ASCII passphrase. */
        const val PASSPHRASE_ITERATIONS = 200

        /** Validation samples, each mutated two different ways. */
        const val VALIDATION_ITERATIONS = 200

        /** Printable ASCII, the range where skipping NFKD is safe. */
        const val ASCII_PASSPHRASE_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 !#\$%&*+-_=?@"
    }

    /**
     * A short, non-reversible label for a mnemonic.
     *
     * Failure messages end up in CI logs. The seeded iteration index already
     * reproduces any failure exactly; this only disambiguates which phrase was
     * in play without printing it.
     */
    private fun fingerprint(words: List<String>): String =
        Blake2b.digest(words.joinToString(" ").encodeToByteArray())
            .copyOfRange(0, 8)
            .toHexStringNoPrefix()

    /** The reference library's words for [entropy]. */
    private fun referenceWords(entropy: ByteArray): List<String> =
        Mnemonics.MnemonicCode(entropy).use { code -> code.map { it } }

    /** The reference library's 64-byte seed for [words] under [passphrase]. */
    private fun referenceSeed(words: List<String>, passphrase: String): ByteArray =
        Mnemonics.MnemonicCode(words.joinToString(" ")).use { code ->
            code.toSeed(passphrase.toCharArray())
        }

    private fun Random.asciiPassphrase(): String {
        val length = nextInt(0, 33)
        return buildString(length) {
            repeat(length) { append(ASCII_PASSPHRASE_ALPHABET[nextInt(ASCII_PASSPHRASE_ALPHABET.length)]) }
        }
    }

    @Test
    fun words_match_the_reference_for_random_entropy() {
        val random = Random(SEED)
        var twelve = 0
        var twentyFour = 0
        repeat(ITERATIONS) { iteration ->
            val entropyLength = if (iteration % 2 == 0) 16 else 32
            val entropy = random.nextBytes(entropyLength)

            val expected = referenceWords(entropy)
            val actual = Bip39.entropyToMnemonic(entropy)

            assertEquals(
                expected,
                actual,
                "entropyToMnemonic disagreed at iteration $iteration (${fingerprint(expected)})",
            )
            if (entropyLength == 16) twelve++ else twentyFour++
        }
        assertEquals(ITERATIONS / 2, twelve)
        assertEquals(ITERATIONS / 2, twentyFour)
    }

    @Test
    fun seeds_match_the_reference_with_an_empty_passphrase() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val entropy = random.nextBytes(if (iteration % 2 == 0) 16 else 32)
            val words = Bip39.entropyToMnemonic(entropy)

            val expected = referenceSeed(words, "")
            val actual = Bip39.toSeed(words)

            assertEquals(64, actual.size, "seed was not 64 bytes at iteration $iteration")
            assertTrue(
                expected.contentEquals(actual),
                "toSeed disagreed at iteration $iteration (${fingerprint(words)})",
            )
        }
    }

    @Test
    fun seeds_match_the_reference_with_a_random_ascii_passphrase() {
        val random = Random(SEED + 1)
        repeat(PASSPHRASE_ITERATIONS) { iteration ->
            val entropy = random.nextBytes(if (iteration % 2 == 0) 16 else 32)
            val words = Bip39.entropyToMnemonic(entropy)
            val passphrase = random.asciiPassphrase()

            val expected = referenceSeed(words, passphrase)
            val actual = Bip39.toSeed(words, passphrase)

            assertTrue(
                expected.contentEquals(actual),
                "toSeed disagreed at iteration $iteration (${fingerprint(words)}, " +
                    "passphrase length ${passphrase.length})",
            )
        }
    }

    @Test
    fun entropy_round_trips_against_the_reference() {
        val random = Random(SEED + 2)
        repeat(VALIDATION_ITERATIONS) { iteration ->
            val entropy = random.nextBytes(if (iteration % 2 == 0) 16 else 32)
            val words = Bip39.entropyToMnemonic(entropy)

            val expected = Mnemonics.MnemonicCode(words.joinToString(" ")).use { it.toEntropy() }
            assertTrue(
                expected.contentEquals(Bip39.mnemonicToEntropy(words)),
                "mnemonicToEntropy disagreed at iteration $iteration (${fingerprint(words)})",
            )
        }
    }

    @Test
    fun validation_matches_the_reference_on_good_and_broken_phrases() {
        val random = Random(SEED + 3)
        repeat(VALIDATION_ITERATIONS) { iteration ->
            val entropy = random.nextBytes(if (iteration % 2 == 0) 16 else 32)
            val words = Bip39.entropyToMnemonic(entropy)

            assertTrue(
                referenceValidates(words),
                "the reference rejected its own phrase at iteration $iteration",
            )
            assertTrue(
                Bip39.validate(words),
                "validate rejected a good phrase at iteration $iteration (${fingerprint(words)})",
            )

            // A flipped checksum bit: every word stays on the list and the word
            // count stays legal, so only the checksum can catch it.
            val flipped = flipChecksumBit(words, random)
            assertFalse(
                referenceValidates(flipped),
                "the reference accepted a flipped checksum at iteration $iteration",
            )
            assertFalse(
                Bip39.validate(flipped),
                "validate accepted a flipped checksum at iteration $iteration " +
                    "(${fingerprint(flipped)})",
            )

            // A word that is not on the list at all.
            val offList = words.toMutableList()
            offList[random.nextInt(offList.size)] = "zzzznotaword"
            assertFalse(
                referenceValidates(offList),
                "the reference accepted an off-list word at iteration $iteration",
            )
            assertFalse(
                Bip39.validate(offList),
                "validate accepted an off-list word at iteration $iteration",
            )
        }
    }

    private fun referenceValidates(words: List<String>): Boolean =
        try {
            Mnemonics.MnemonicCode(words.joinToString(" ")).use { it.validate() }
            true
        } catch (_: Mnemonics.ChecksumException) {
            false
        } catch (_: Mnemonics.WordCountException) {
            false
        } catch (_: Mnemonics.InvalidWordException) {
            false
        }

    /**
     * Replaces the last word with a different one, which flips at least one
     * checksum bit while leaving the phrase otherwise well formed.
     *
     * The last word carries all 4 (12-word) or 8 (24-word) checksum bits, so a
     * substitution there changes the checksum, the trailing entropy bits, or
     * both, and the two stop agreeing. The loop retries the roughly 1-in-16
     * (12-word) case where the new entropy happens to check out anyway.
     *
     * The REFERENCE decides when to stop, never [Bip39.validate]: looping on our
     * own answer would only ever hand back phrases we already reject, which is
     * the thing under test.
     */
    private fun flipChecksumBit(words: List<String>, random: Random): List<String> {
        val mutated = words.toMutableList()
        val lastIndex = mutated.lastIndex
        while (true) {
            val candidate = Bip39.WORDLIST[random.nextInt(Bip39.WORDLIST.size)]
            if (candidate == words[lastIndex]) continue
            mutated[lastIndex] = candidate
            if (!referenceValidates(mutated)) return mutated
        }
    }
}
