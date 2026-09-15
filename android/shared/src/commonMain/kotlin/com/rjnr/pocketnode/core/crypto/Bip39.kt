package com.rjnr.pocketnode.core.crypto

import org.kotlincrypto.hash.sha2.SHA256

/**
 * BIP-39 mnemonic generation, validation and seed derivation, English only.
 *
 * Replaces `cash.z.ecc.android:kotlin-bip39`, which publishes JVM artifacts only
 * and so cannot reach iOS (#507). The library survives as a `:shared`
 * `androidHostTest` differential-test dependency, the same arrangement the CKB
 * Java SDK has in #454: a disagreement in `Bip39DifferentialTest` means this
 * file is wrong, not the test.
 *
 * ## Normalisation
 *
 * BIP-39 specifies NFKD normalisation of both the mnemonic and the passphrase
 * before they enter PBKDF2. The English wordlist is pure ASCII and already NFKD,
 * so the mnemonic side is a no-op. The passphrase side is NOT: the Kotlin/Native
 * stdlib has no NFKD, so this implementation feeds the passphrase through
 * unchanged. **Only ASCII passphrases are guaranteed to match the reference
 * implementations.** A passphrase carrying composed accents, full-width
 * characters or other NFKD-affected codepoints would derive a different seed
 * here than in a wallet that normalises. The app has never exposed a passphrase
 * field, so nothing shipped depends on the non-ASCII behaviour; a UI that adds
 * one must either restrict input to ASCII or land NFKD first.
 */
object Bip39 {

    /** PBKDF2 round count fixed by BIP-39. */
    private const val SEED_ITERATIONS = 2048

    /** BIP-39 seed length, in bytes. */
    private const val SEED_LENGTH = 64

    /** Every BIP-39 word encodes 11 bits, hence a 2048-word list. */
    private const val BITS_PER_WORD = 11

    /** The official English wordlist, in index order. */
    val WORDLIST: List<String> = BIP39_ENGLISH_WORDLIST.asList()

    private val wordIndex: Map<String, Int> =
        BIP39_ENGLISH_WORDLIST.withIndex().associate { (index, word) -> word to index }

    /**
     * A fresh mnemonic of [wordCount] words, 12 or 24.
     *
     * Every bit comes from [entropy]; nothing here reaches for a default RNG, so
     * a caller cannot accidentally generate a wallet from a seeded PRNG.
     *
     * @throws IllegalArgumentException if [wordCount] is not 12 or 24, or if
     *   [entropy] returns the wrong number of bytes.
     */
    fun generate(wordCount: Int, entropy: EntropySource): List<String> {
        val entropyLength = when (wordCount) {
            12 -> 16
            24 -> 32
            else -> throw IllegalArgumentException("wordCount must be 12 or 24, was $wordCount")
        }
        val bytes = entropy.nextBytes(entropyLength)
        require(bytes.size == entropyLength) {
            "EntropySource returned ${bytes.size} bytes, expected $entropyLength"
        }
        return entropyToMnemonic(bytes)
    }

    /**
     * True if [words] is a well-formed BIP-39 mnemonic: a valid length, every
     * word on the list, and a checksum that matches.
     *
     * Words are compared verbatim. Callers normalise case and whitespace before
     * they get here, and quietly repairing input would let a wrong-looking
     * phrase through.
     */
    fun validate(words: List<String>): Boolean =
        try {
            mnemonicToEntropy(words)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * The 64-byte BIP-39 seed for [words] under [passphrase].
     *
     * PBKDF2-HMAC-SHA512 over the space-joined mnemonic, salted with
     * `"mnemonic" + passphrase`, 2048 rounds. See the class KDoc for the
     * ASCII-passphrase caveat.
     *
     * Deliberately does not validate [words] first: importing a wallet whose
     * checksum a previous tool got wrong must still reproduce the same seed.
     */
    fun toSeed(words: List<String>, passphrase: String = ""): ByteArray =
        Pbkdf2.hmacSha512(
            password = words.joinToString(" ").encodeToByteArray(),
            salt = ("mnemonic" + passphrase).encodeToByteArray(),
            iterations = SEED_ITERATIONS,
            keyLengthBytes = SEED_LENGTH,
        )

    /**
     * Encodes [entropy] as a mnemonic: 16, 20, 24, 28 or 32 bytes in, 12, 15,
     * 18, 21 or 24 words out.
     *
     * The trailing `ENT / 32` bits are the leading bits of `SHA-256(entropy)`.
     */
    fun entropyToMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size in 16..32 && entropy.size % 4 == 0) {
            "Entropy must be 16..32 bytes in multiples of 4, was ${entropy.size}"
        }
        val entropyBits = entropy.size * 8
        val checksumBits = entropyBits / 32
        val checksum = SHA256().digest(entropy)
        val totalBits = entropyBits + checksumBits

        val words = ArrayList<String>(totalBits / BITS_PER_WORD)
        var index = 0
        var held = 0
        for (position in 0 until totalBits) {
            val bit = if (position < entropyBits) {
                bitAt(entropy, position)
            } else {
                bitAt(checksum, position - entropyBits)
            }
            index = (index shl 1) or bit
            if (++held == BITS_PER_WORD) {
                words.add(WORDLIST[index])
                index = 0
                held = 0
            }
        }
        return words
    }

    /**
     * Decodes [words] back to its entropy, verifying the checksum.
     *
     * @throws IllegalArgumentException on a bad word count, an off-list word or
     *   a checksum mismatch. The message never quotes the offending word: these
     *   are seed phrases, and this failure path reaches `Result.onFailure` and
     *   from there a log line.
     */
    fun mnemonicToEntropy(words: List<String>): ByteArray {
        require(words.size in 12..24 && words.size % 3 == 0) {
            "Mnemonic must be 12, 15, 18, 21 or 24 words, was ${words.size}"
        }
        val totalBits = words.size * BITS_PER_WORD
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits

        val entropy = ByteArray(entropyBits / 8)
        var actualChecksum = 0
        var position = 0
        for (word in words) {
            val index = wordIndex[word]
                ?: throw IllegalArgumentException("Mnemonic contains a word that is not on the BIP-39 English list")
            for (shift in BITS_PER_WORD - 1 downTo 0) {
                val bit = (index ushr shift) and 1
                if (position < entropyBits) {
                    if (bit == 1) {
                        val byteIndex = position / 8
                        entropy[byteIndex] =
                            (entropy[byteIndex].toInt() or (1 shl (7 - position % 8))).toByte()
                    }
                } else {
                    actualChecksum = (actualChecksum shl 1) or bit
                }
                position++
            }
        }

        // checksumBits is 4..8, so the whole checksum lives in the first hash byte.
        val expectedChecksum = (SHA256().digest(entropy)[0].toInt() and 0xFF) ushr (8 - checksumBits)
        require(actualChecksum == expectedChecksum) { "Mnemonic checksum does not match" }
        return entropy
    }

    /** The [position]-th bit of [bytes], counting from the most significant bit of byte 0. */
    private fun bitAt(bytes: ByteArray, position: Int): Int =
        (bytes[position / 8].toInt() ushr (7 - position % 8)) and 1
}
