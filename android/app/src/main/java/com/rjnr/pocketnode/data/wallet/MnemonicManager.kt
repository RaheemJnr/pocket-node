package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.crypto.Bip32
import com.rjnr.pocketnode.core.crypto.Bip39
import com.rjnr.pocketnode.core.crypto.EntropySource
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MnemonicManager @Inject constructor() {

    enum class WordCount(val count: Int) {
        TWELVE(12),
        TWENTY_FOUR(24)
    }

    /**
     * Generate a new BIP39 mnemonic phrase.
     * @return list of English words (12 or 24)
     */
    fun generateMnemonic(wordCount: WordCount = WordCount.TWELVE): List<String> =
        Bip39.generate(wordCount.count, SecureRandomEntropySource)

    /**
     * Validate a BIP39 mnemonic phrase.
     * Checks word count, word list membership, and checksum.
     */
    fun validateMnemonic(words: List<String>): Boolean = Bip39.validate(words)

    /**
     * Derive a 512-bit (64-byte) seed from a mnemonic using PBKDF2-SHA512.
     * @param words the mnemonic word list
     * @param passphrase optional BIP39 passphrase (default empty)
     * @return 64-byte seed
     */
    fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray =
        Bip39.toSeed(words, passphrase)

    /**
     * Derive a 32-byte secp256k1 private key from a BIP39 seed using BIP32/BIP44.
     * Derivation path: m/44'/309'/{accountIndex}'/{chainIndex}/{addressIndex}
     *
     * [chainIndex] is BIP44's change level: 0 = receiving chain, 1 = change
     * chain. Everything shipped before #382 Tier 2 used chain 0 only; the
     * default keeps those call sites byte-identical.
     */
    fun derivePrivateKey(
        seed: ByteArray,
        accountIndex: Int = 0,
        chainIndex: Int = 0,
        addressIndex: Int = 0
    ): ByteArray = Bip32.deriveCkbPrivateKey(seed, accountIndex, chainIndex, addressIndex)

    /**
     * Convenience: mnemonic words -> private key in one call.
     * Derivation path: m/44'/309'/0'/0/0
     */
    fun mnemonicToPrivateKey(
        words: List<String>,
        passphrase: String = ""
    ): ByteArray {
        val seed = mnemonicToSeed(words, passphrase)
        return derivePrivateKey(seed)
    }

    /**
     * `commonMain` has no `SecureRandom`, so [Bip39.generate] takes its entropy from
     * the platform. This is Android's: one `SecureRandom` instance, reused.
     */
    private object SecureRandomEntropySource : EntropySource {
        private val random = SecureRandom()

        override fun nextBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)
    }
}
