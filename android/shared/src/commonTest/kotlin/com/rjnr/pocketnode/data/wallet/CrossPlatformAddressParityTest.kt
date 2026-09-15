package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.crypto.Bip32
import com.rjnr.pocketnode.core.crypto.Bip39
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform address-parity fixture (#517).
 *
 * The pipeline this test drives — [Bip39.toSeed] -> [Bip32.deriveCkbPrivateKey]
 * -> [WalletDerivation.walletInfo] — is exactly what iOS's `WalletCreator`
 * walks for `m/44'/309'/0'/0/0` (`Bip39.shared.toSeed`,
 * `Bip32.shared.deriveCkbPrivateKey`, `WalletDerivation.shared.walletInfo`),
 * and what `OnboardingUITests.testImportingTheTestPhraseShowsThePinnedTestnetAddress`
 * asserts through the real iOS UI for the same phrase. Runs on both
 * `:shared:testAndroidHostTest` (JVM) and `:shared:iosSimulatorArm64Test`
 * (Kotlin/Native), so a derivation drift between those two targets — not only
 * between the Android and iOS apps — fails here first.
 *
 * The phrase is the standard all-"abandon" BIP-39 test vector. Its seed is
 * pinned independently in
 * [com.rjnr.pocketnode.core.crypto.Bip39Test.toSeed_empty_passphrase_matches_bip39_reference],
 * and the private key and both addresses below are asserted byte-for-byte
 * against iOS's `WalletCreatorTests.testImportingTheTestPhraseDerivesThePinnedAddresses`
 * and against `WalletCreatorTests.testTestPrivateKeyHex`/`testTestnetAddress`/
 * `testMainnetAddress`.
 */
class CrossPlatformAddressParityTest {

    private companion object {
        val MNEMONIC = List(11) { "abandon" } + "about"

        const val EXPECTED_PRIVATE_KEY_HEX =
            "b217d9a18ff657c99872cc11a2fa2aa3e970cef8c6faa7d6e424bf057cb3707b"
        const val EXPECTED_TESTNET_ADDRESS =
            "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"
        const val EXPECTED_MAINNET_ADDRESS =
            "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft"
    }

    @Test
    fun mnemonicDerivesThePinnedPrivateKeyAndBothAddresses() {
        val seed = Bip39.toSeed(MNEMONIC)
        val privateKey = Bip32.deriveCkbPrivateKey(seed, accountIndex = 0, chainIndex = 0, addressIndex = 0)

        assertEquals(EXPECTED_PRIVATE_KEY_HEX, privateKey.toHexStringNoPrefix())

        val info = WalletDerivation.walletInfo(privateKey)
        assertEquals(EXPECTED_TESTNET_ADDRESS, info.testnetAddress)
        assertEquals(EXPECTED_MAINNET_ADDRESS, info.mainnetAddress)
    }
}
