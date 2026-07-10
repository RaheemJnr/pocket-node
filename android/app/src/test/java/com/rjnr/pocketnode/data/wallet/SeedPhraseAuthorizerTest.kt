package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The V2 gap-limit scan/sweep bug (#408): reading the seed for an auth-bound
 * (kdfVersion=2) wallet needs an authenticated Cipher from a BiometricPrompt.
 * [SeedPhraseAuthorizer] wraps [WalletKeyReader.readKeyMaterial] (which drives
 * that prompt) and maps its result to a small outcome the scan/sweep call sites
 * can act on. These lock in that mapping — the one piece of the fix that is pure
 * and testable without an Activity or the biometric stack.
 */
class SeedPhraseAuthorizerTest {

    private fun success(mnemonic: String?) = WalletKeyReader.MaterialResult.Success(
        privateKey = ByteArray(32),
        mnemonic = mnemonic,
        walletType = "mnemonic",
        mnemonicBackedUp = true,
    )

    @Test
    fun `success with a mnemonic yields the split words`() {
        val r = SeedPhraseAuthorizer.toSeedResult(
            success("angle bonus strike comic jelly mom decrease galaxy error tackle together woman")
        )
        assertTrue(r is SeedPhraseAuthorizer.SeedResult.Words)
        assertEquals(12, (r as SeedPhraseAuthorizer.SeedResult.Words).words.size)
        assertEquals("angle", r.words.first())
        assertEquals("woman", r.words.last())
    }

    @Test
    fun `extra whitespace between words is collapsed`() {
        val r = SeedPhraseAuthorizer.toSeedResult(success("  alpha   beta \n gamma  "))
        assertEquals(listOf("alpha", "beta", "gamma"), (r as SeedPhraseAuthorizer.SeedResult.Words).words)
    }

    @Test
    fun `raw-key wallet (null mnemonic) fails, not empty words`() {
        // Imported private-key wallets have no seed to derive sub-accounts from.
        val r = SeedPhraseAuthorizer.toSeedResult(success(null))
        assertTrue(r is SeedPhraseAuthorizer.SeedResult.Failed)
    }

    @Test
    fun `blank mnemonic fails rather than producing empty words`() {
        val r = SeedPhraseAuthorizer.toSeedResult(success("   "))
        assertTrue(r is SeedPhraseAuthorizer.SeedResult.Failed)
    }

    @Test
    fun `user cancel maps to Cancelled`() {
        val r = SeedPhraseAuthorizer.toSeedResult(WalletKeyReader.MaterialResult.Cancelled)
        assertTrue(r is SeedPhraseAuthorizer.SeedResult.Cancelled)
    }

    @Test
    fun `biometric enrollment change maps to KeyInvalidated`() {
        val r = SeedPhraseAuthorizer.toSeedResult(WalletKeyReader.MaterialResult.KeyInvalidated)
        assertTrue(r is SeedPhraseAuthorizer.SeedResult.KeyInvalidated)
    }

    @Test
    fun `auth error carries its message into Failed`() {
        val r = SeedPhraseAuthorizer.toSeedResult(
            WalletKeyReader.MaterialResult.AuthError(7, "Too many attempts")
        )
        assertEquals("Too many attempts", (r as SeedPhraseAuthorizer.SeedResult.Failed).reason)
    }

    @Test
    fun `not-available carries its reason into Failed`() {
        val r = SeedPhraseAuthorizer.toSeedResult(
            WalletKeyReader.MaterialResult.NotAvailable("no key_material row")
        )
        assertEquals("no key_material row", (r as SeedPhraseAuthorizer.SeedResult.Failed).reason)
    }
}
