package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #382 Tier 2: gap-limit discovery needs keys along the receiving AND change
 * chains — m/44'/309'/0'/{chain}/{index} — while everything shipped so far
 * hardcodes chain 0. The chain segment is a normal (non-hardened) BIP32
 * child, exactly like the address index.
 *
 * Contracts: chain 0 must be byte-identical to the legacy two-axis call
 * (a change here would re-derive every existing wallet to a different key),
 * chain 1 must differ, and derivation must be deterministic.
 */
class ChainDerivationTest {

    private val manager = MnemonicManager()

    private val words =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")

    private val seed by lazy { manager.mnemonicToSeed(words) }

    @Test
    fun `chain 0 matches the legacy derivation for every address index`() {
        for (i in 0..3) {
            val legacy = manager.derivePrivateKey(seed, accountIndex = 0, addressIndex = i)
            val explicit = manager.derivePrivateKey(
                seed, accountIndex = 0, chainIndex = 0, addressIndex = i
            )
            assertArrayEquals("index $i", legacy, explicit)
        }
    }

    @Test
    fun `change chain derives a different key than the receiving chain`() {
        val receiving = manager.derivePrivateKey(seed, accountIndex = 0, chainIndex = 0, addressIndex = 0)
        val change = manager.derivePrivateKey(seed, accountIndex = 0, chainIndex = 1, addressIndex = 0)
        assertFalse(receiving.contentEquals(change))
    }

    @Test
    fun `change-chain derivation is deterministic`() {
        val a = manager.derivePrivateKey(seed, accountIndex = 0, chainIndex = 1, addressIndex = 7)
        val b = manager.derivePrivateKey(seed, accountIndex = 0, chainIndex = 1, addressIndex = 7)
        assertArrayEquals(a, b)
    }

    @Test
    fun `distinct change-chain indices derive distinct keys`() {
        val keys = (0..5).map {
            manager.derivePrivateKey(seed, accountIndex = 0, chainIndex = 1, addressIndex = it)
                .joinToString("") { b -> "%02x".format(b) }
        }
        assertEquals(6, keys.toSet().size)
    }
}
