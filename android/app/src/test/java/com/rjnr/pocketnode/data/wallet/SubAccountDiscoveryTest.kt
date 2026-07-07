package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Discovery derives candidate lock args at m/44'/309'/N'/0/0 for N=1..window.
 * The contract that matters: deterministic (same seed -> same args, so a
 * re-import finds the same sub-accounts), distinct per index, index 0 (the
 * parent) excluded, and args match what createSubAccount would produce for
 * the same index — otherwise discovery would look for the wrong scripts.
 */
@RunWith(RobolectricTestRunner::class)
class SubAccountDiscoveryTest {

    private lateinit var discovery: SubAccountDiscovery
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var keyManager: KeyManager

    private val words =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        discovery = SubAccountDiscovery(mnemonicManager, keyManager)
    }

    @Test
    fun `candidates are deterministic across calls`() {
        val a = discovery.deriveCandidates(words)
        val b = discovery.deriveCandidates(words)
        assertEquals(a, b)
    }

    @Test
    fun `window of N yields indices 1 through N with distinct args`() {
        val candidates = discovery.deriveCandidates(words, window = 5)
        assertEquals((1..5).toList(), candidates.map { it.accountIndex })
        assertEquals(5, candidates.map { it.scriptArgs }.toSet().size)
        candidates.forEach { assertTrue(it.scriptArgs.startsWith("0x")) }
    }

    @Test
    fun `parent index 0 is not a candidate`() {
        val parentArgs = keyManager
            .deriveLockScript(
                keyManager.derivePublicKey(mnemonicManager.mnemonicToPrivateKey(words))
            )
            .args
        discovery.deriveCandidates(words).forEach {
            assertNotEquals(parentArgs, it.scriptArgs)
        }
    }

    @Test
    fun `account candidates carry their derivation path`() {
        val candidate = discovery.deriveCandidates(words).first { it.accountIndex == 2 }
        assertEquals("m/44'/309'/2'/0/0", candidate.derivationPath)
    }

    // --- #382 Tier 2: receiving/change-chain candidates -------------------

    @Test
    fun `chain candidates cover both chains through the window, excluding the parent slot`() {
        val candidates = discovery.deriveChainCandidates(words, window = 20)
        // chains {0,1} x indices {0..20} minus chain0/index0 (the parent) = 41
        assertEquals(41, candidates.size)
        assertEquals(41, candidates.map { it.scriptArgs }.toSet().size)
        assertTrue(candidates.any { it.derivationPath == "m/44'/309'/0'/0/1" })
        assertTrue(candidates.any { it.derivationPath == "m/44'/309'/0'/1/0" })
        assertTrue(candidates.any { it.derivationPath == "m/44'/309'/0'/1/20" })
        assertTrue(candidates.none { it.derivationPath == "m/44'/309'/0'/0/0" })
        candidates.forEach {
            assertEquals(0, it.accountIndex)
            assertTrue(it.scriptArgs.startsWith("0x"))
        }
    }

    @Test
    fun `chain candidates are deterministic across calls`() {
        assertEquals(
            discovery.deriveChainCandidates(words),
            discovery.deriveChainCandidates(words),
        )
    }

    @Test
    fun `chain candidates exclude the parent args`() {
        val parentArgs = keyManager
            .deriveLockScript(
                keyManager.derivePublicKey(mnemonicManager.mnemonicToPrivateKey(words))
            )
            .args
        discovery.deriveChainCandidates(words).forEach {
            assertNotEquals(parentArgs, it.scriptArgs)
        }
    }

    @Test
    fun `chain candidate args match direct chain derivation`() {
        // A mismatch means discovery would scan scripts the seed never owned.
        val seed = mnemonicManager.mnemonicToSeed(words)
        val expected = keyManager
            .deriveLockScript(
                keyManager.derivePublicKey(
                    mnemonicManager.derivePrivateKey(
                        seed, accountIndex = 0, chainIndex = 1, addressIndex = 3
                    )
                )
            )
            .args
        val candidate = discovery.deriveChainCandidates(words)
            .first { it.derivationPath == "m/44'/309'/0'/1/3" }
        assertEquals(expected, candidate.scriptArgs)
    }

    @Test
    fun `candidate args match createSubAccount derivation for the same index`() {
        // Mirror WalletRepository.createSubAccount's exact derivation for
        // index 2; a mismatch means discovery scans scripts no restored
        // sub-account would ever own.
        val seed = mnemonicManager.mnemonicToSeed(words)
        val expected = keyManager
            .deriveLockScript(
                keyManager.derivePublicKey(
                    mnemonicManager.derivePrivateKey(seed, accountIndex = 2)
                )
            )
            .args
        val candidate = discovery.deriveCandidates(words).first { it.accountIndex == 2 }
        assertEquals(expected, candidate.scriptArgs)
    }
}
