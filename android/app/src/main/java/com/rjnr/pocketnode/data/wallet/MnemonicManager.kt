package com.rjnr.pocketnode.data.wallet

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.nervos.ckb.crypto.secp256k1.ECKeyPair
import java.math.BigInteger
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
    fun generateMnemonic(wordCount: WordCount = WordCount.TWELVE): List<String> {
        val libWordCount = when (wordCount) {
            WordCount.TWELVE -> Mnemonics.WordCount.COUNT_12
            WordCount.TWENTY_FOUR -> Mnemonics.WordCount.COUNT_24
        }
        return Mnemonics.MnemonicCode(libWordCount).use { code ->
            code.map { it }
        }
    }

    /**
     * Validate a BIP39 mnemonic phrase.
     * Checks word count, word list membership, and checksum.
     */
    fun validateMnemonic(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        return try {
            Mnemonics.MnemonicCode(words.joinToString(" ")).use { it.validate() }
            true
        } catch (_: Mnemonics.ChecksumException) {
            false
        } catch (_: Mnemonics.WordCountException) {
            false
        } catch (_: Mnemonics.InvalidWordException) {
            false
        }
    }

    /**
     * Derive a 512-bit (64-byte) seed from a mnemonic using PBKDF2-SHA512.
     * @param words the mnemonic word list
     * @param passphrase optional BIP39 passphrase (default empty)
     * @return 64-byte seed
     */
    fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray {
        return Mnemonics.MnemonicCode(words.joinToString(" ")).use { code ->
            code.toSeed(passphrase.toCharArray())
        }
    }

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
    ): ByteArray {
        require(seed.size == 64) { "Seed must be 64 bytes" }

        var key = deriveMasterKey(seed)
        // m/44' -> m/44'/309' -> m/44'/309'/accountIndex'
        key = deriveHardenedChild(key, 44)
        key = deriveHardenedChild(key, 309)
        key = deriveHardenedChild(key, accountIndex)
        // m/44'/309'/accountIndex'/chainIndex -> .../chainIndex/addressIndex
        key = deriveNormalChild(key, chainIndex)
        key = deriveNormalChild(key, addressIndex)

        return key.key
    }

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

    // -- BIP32 internals --

    private class ExtendedKey(
        val key: ByteArray,       // 32 bytes - private key
        val chainCode: ByteArray  // 32 bytes
    )

    private fun deriveMasterKey(seed: ByteArray): ExtendedKey {
        val hmacResult = hmacSha512("Bitcoin seed".toByteArray(), seed)
        return ExtendedKey(
            key = hmacResult.copyOfRange(0, 32),
            chainCode = hmacResult.copyOfRange(32, 64)
        )
    }

    /**
     * Hardened child derivation: data = 0x00 || parentKey || (index + 0x80000000) BE
     */
    private fun deriveHardenedChild(parent: ExtendedKey, index: Int): ExtendedKey {
        val data = ByteArray(37)
        data[0] = 0x00
        System.arraycopy(parent.key, 0, data, 1, 32)
        putBE32(data, 33, index.toLong() + 0x80000000L)
        return computeChildKey(parent, data)
    }

    /**
     * Normal child derivation: data = compressedPubKey || index BE
     */
    private fun deriveNormalChild(parent: ExtendedKey, index: Int): ExtendedKey {
        val compressedPubKey = ECKeyPair.create(BigInteger(1, parent.key))
            .getEncodedPublicKey(true) // 33 bytes

        val data = ByteArray(37)
        System.arraycopy(compressedPubKey, 0, data, 0, 33)
        putBE32(data, 33, index.toLong())
        return computeChildKey(parent, data)
    }

    private fun computeChildKey(parent: ExtendedKey, data: ByteArray): ExtendedKey {
        val hmacResult = hmacSha512(parent.chainCode, data)

        val il = BigInteger(1, hmacResult.copyOfRange(0, 32))
        require(il < SECP256K1_N) { "Invalid derived key (>= curve order)" }

        val childKey = il.add(BigInteger(1, parent.key)).mod(SECP256K1_N)
        require(childKey != BigInteger.ZERO) { "Invalid derived key (zero)" }

        return ExtendedKey(
            key = childKey.toByteArray32(),
            chainCode = hmacResult.copyOfRange(32, 64)
        )
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA512Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val result = ByteArray(64)
        hmac.doFinal(result, 0)
        return result
    }

    private fun putBE32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value shr 24 and 0xFF).toByte()
        buf[offset + 1] = (value shr 16 and 0xFF).toByte()
        buf[offset + 2] = (value shr 8 and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    companion object {
        private val SECP256K1_N = BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
        )

        /** Convert BigInteger to exactly 32 bytes, zero-padded. */
        private fun BigInteger.toByteArray32(): ByteArray {
            val bytes = this.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> error("Key too large: ${bytes.size} bytes")
            }
        }
    }
}
