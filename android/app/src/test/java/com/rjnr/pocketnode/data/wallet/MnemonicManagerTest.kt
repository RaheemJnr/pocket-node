package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MnemonicManagerTest {

    private lateinit var manager: MnemonicManager

    // Standard BIP39 test mnemonic (all "abandon" + "about")
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    @Before
    fun setUp() {
        manager = MnemonicManager()
    }

    // -- Generation tests --

    @Test
    fun `generateMnemonic twelve returns 12 words`() {
        val words = manager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        assertEquals(12, words.size)
    }

    @Test
    fun `generateMnemonic twentyFour returns 24 words`() {
        val words = manager.generateMnemonic(MnemonicManager.WordCount.TWENTY_FOUR)
        assertEquals(24, words.size)
    }

    @Test
    fun `generated mnemonic is valid`() {
        val words = manager.generateMnemonic()
        assertTrue(manager.validateMnemonic(words))
    }

    @Test
    fun `two generations produce different mnemonics`() {
        val words1 = manager.generateMnemonic()
        val words2 = manager.generateMnemonic()
        assertNotEquals(words1, words2)
    }

    // -- Validation tests --

    @Test
    fun `validateMnemonic valid twelve words returns true`() {
        assertTrue(manager.validateMnemonic(testMnemonic))
    }

    @Test
    fun `validateMnemonic invalid word returns false`() {
        val invalid = testMnemonic.toMutableList()
        invalid[0] = "zzzznotaword"
        assertFalse(manager.validateMnemonic(invalid))
    }

    @Test
    fun `validateMnemonic wrong checksum returns false`() {
        // Swap first two words to break checksum
        val invalid = testMnemonic.toMutableList()
        invalid[0] = "zoo"
        assertFalse(manager.validateMnemonic(invalid))
    }

    @Test
    fun `validateMnemonic wrong count returns false`() {
        val invalid = testMnemonic.take(11)
        assertFalse(manager.validateMnemonic(invalid))
    }

    @Test
    fun `validateMnemonic empty returns false`() {
        assertFalse(manager.validateMnemonic(emptyList()))
    }

    // -- Seed derivation tests --

    @Test
    fun `mnemonicToSeed known test vector`() {
        // BIP39 test vector: "abandon...about" with empty passphrase
        val seed = manager.mnemonicToSeed(testMnemonic)
        val seedHex = seed.toHex()
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            seedHex
        )
    }

    @Test
    fun `mnemonicToSeed returns 64 bytes`() {
        val seed = manager.mnemonicToSeed(testMnemonic)
        assertEquals(64, seed.size)
    }

    @Test
    fun `mnemonicToSeed rejects a bad checksum`() {
        // Every word is on the list and the count is legal, so only the checksum
        // catches this. Deriving a seed anyway would hand the user a valid
        // looking key for an empty address, with no error to explain it.
        val invalid = testMnemonic.toMutableList()
        invalid[11] = "abandon"
        assertFalse(manager.validateMnemonic(invalid))
        assertThrows(IllegalArgumentException::class.java) {
            manager.mnemonicToSeed(invalid)
        }
    }

    @Test
    fun `mnemonicToSeed rejects a word that is not on the list`() {
        val invalid = testMnemonic.toMutableList()
        invalid[0] = "zzzznotaword"
        assertThrows(IllegalArgumentException::class.java) {
            manager.mnemonicToSeed(invalid)
        }
    }

    @Test
    fun `mnemonicToSeed different passphrase produces different seed`() {
        val seed1 = manager.mnemonicToSeed(testMnemonic, "")
        val seed2 = manager.mnemonicToSeed(testMnemonic, "mypassphrase")
        assertFalse(seed1.contentEquals(seed2))
    }

    // -- BIP32 master key derivation test --

    @Test
    fun `derivePrivateKey returns 32 bytes`() {
        val seed = manager.mnemonicToSeed(testMnemonic)
        val key = manager.derivePrivateKey(seed)
        assertEquals(32, key.size)
    }

    @Test
    fun `derivePrivateKey invalid seed length throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            manager.derivePrivateKey(ByteArray(32))
        }
    }

    // -- Determinism tests --

    @Test
    fun `mnemonicToPrivateKey same mnemonic produces same key`() {
        val key1 = manager.mnemonicToPrivateKey(testMnemonic)
        val key2 = manager.mnemonicToPrivateKey(testMnemonic)
        assertArrayEquals(key1, key2)
    }

    @Test
    fun `mnemonicToPrivateKey different passphrase produces different key`() {
        val key1 = manager.mnemonicToPrivateKey(testMnemonic, "")
        val key2 = manager.mnemonicToPrivateKey(testMnemonic, "password")
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `derivePrivateKey different account index produces different key`() {
        val seed = manager.mnemonicToSeed(testMnemonic)
        val key0 = manager.derivePrivateKey(seed, accountIndex = 0)
        val key1 = manager.derivePrivateKey(seed, accountIndex = 1)
        assertFalse(key0.contentEquals(key1))
    }

    @Test
    fun `derivePrivateKey different address index produces different key`() {
        val seed = manager.mnemonicToSeed(testMnemonic)
        val key0 = manager.derivePrivateKey(seed, addressIndex = 0)
        val key1 = manager.derivePrivateKey(seed, addressIndex = 1)
        assertFalse(key0.contentEquals(key1))
    }

    // -- Helper --

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
