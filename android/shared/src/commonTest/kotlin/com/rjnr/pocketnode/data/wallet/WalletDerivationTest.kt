package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexString
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.gateway.models.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * Known-answer tests for the shared wallet derivation moved out of `KeyManager`
 * (#511).
 *
 * The expected public key, lock args and both addresses below were PRODUCED BY
 * THE CKB JAVA SDK (`ECKeyPair` + `Blake2b` + `org.nervos.ckb.utils.address.Address`)
 * for the fixed private key [PRIVATE_KEY_HEX], and are re-checked against the
 * live SDK on every run by `WalletDerivationDifferentialTest.pinnedVectorMatchesTheSdk`
 * in `androidHostTest`. They are pinned here so `commonTest` — which also runs
 * on the iOS simulator, where the JVM SDK cannot — still fails loudly if the
 * derivation ever drifts. A mismatch is a derivation bug, not a test bug.
 */
class WalletDerivationTest {

    private companion object {
        const val PRIVATE_KEY_HEX =
            "0x1111111111111111111111111111111111111111111111111111111111111111"
        const val EXPECTED_PUBLIC_KEY =
            "0x034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"
        const val EXPECTED_LOCK_ARGS = "0xf949a9cc83edefcd580eb3f0f3bae187c4d008db"
        const val EXPECTED_TESTNET_ADDRESS =
            "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq0efx5ueqldalx4sr4n7rem4cv8cngq3kcyk4ydl"
        const val EXPECTED_MAINNET_ADDRESS =
            "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq0efx5ueqldalx4sr4n7rem4cv8cngq3kc2y7t88"
    }

    private val privateKey = PRIVATE_KEY_HEX.hexToByteArray()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun publicKeyMatchesThePinnedVector() {
        assertEquals(EXPECTED_PUBLIC_KEY, WalletDerivation.publicKey(privateKey).toHexString())
    }

    @Test
    fun lockScriptMatchesThePinnedVector() {
        val script = WalletDerivation.lockScript(WalletDerivation.publicKey(privateKey))
        assertEquals(Script.SECP256K1_CODE_HASH, script.codeHash)
        assertEquals("type", script.hashType)
        assertEquals(EXPECTED_LOCK_ARGS, script.args)
    }

    @Test
    fun walletInfoMatchesThePinnedVectorOnBothNetworks() {
        val info = WalletDerivation.walletInfo(privateKey)
        assertEquals(EXPECTED_PUBLIC_KEY, info.publicKey)
        assertEquals(EXPECTED_LOCK_ARGS, info.script.args)
        assertEquals(EXPECTED_TESTNET_ADDRESS, info.testnetAddress)
        assertEquals(EXPECTED_MAINNET_ADDRESS, info.mainnetAddress)
    }

    @Test
    fun lockScriptFromAddressRoundTripsTheKeyDerivedScript() {
        val fromKey = WalletDerivation.lockScript(WalletDerivation.publicKey(privateKey))
        assertEquals(fromKey, WalletDerivation.lockScriptFromAddress(EXPECTED_TESTNET_ADDRESS))
        assertEquals(fromKey, WalletDerivation.lockScriptFromAddress(EXPECTED_MAINNET_ADDRESS))
    }

    @Test
    fun walletInfoFromAddressesKeepsBothAddressesAndDropsThePublicKey() {
        val info = WalletDerivation.walletInfoFromAddresses(
            testnetAddress = EXPECTED_TESTNET_ADDRESS,
            mainnetAddress = EXPECTED_MAINNET_ADDRESS,
        )
        assertEquals("", info.publicKey)
        assertEquals(EXPECTED_LOCK_ARGS, info.script.args)
        assertEquals(EXPECTED_TESTNET_ADDRESS, info.testnetAddress)
        assertEquals(EXPECTED_MAINNET_ADDRESS, info.mainnetAddress)
    }

    /** A mainnet-only wallet row has a blank testnet address; the script still resolves. */
    @Test
    fun walletInfoFromAddressesFallsBackToMainnetWhenTestnetIsBlank() {
        val info = WalletDerivation.walletInfoFromAddresses(
            testnetAddress = "",
            mainnetAddress = EXPECTED_MAINNET_ADDRESS,
        )
        assertEquals(EXPECTED_LOCK_ARGS, info.script.args)
        assertEquals("", info.testnetAddress)
        assertEquals(EXPECTED_MAINNET_ADDRESS, info.mainnetAddress)
    }

    // --- WalletKeyBundle codec ---

    /** `privateKeyHex` is lowercase and UNPREFIXED: the stored V2 shape. */
    @Test
    fun encodePlaintextBundleWritesUnprefixedLowercaseHex() {
        val bundle = WalletDerivation.encodePlaintextBundle(privateKey, mnemonic = null)
        assertEquals("11".repeat(32), bundle.privateKeyHex)
        assertNull(bundle.mnemonic)
    }

    @Test
    fun encodePlaintextBundleJoinsTheMnemonicWithSingleSpaces() {
        val words = listOf("abandon", "ability", "able", "about")
        val bundle = WalletDerivation.encodePlaintextBundle(privateKey, words)
        assertEquals("abandon ability able about", bundle.mnemonic)
    }

    @Test
    fun bundleRoundTripsThroughJsonWithAMnemonic() {
        val bundle = WalletDerivation.encodePlaintextBundle(
            privateKey,
            listOf("abandon", "ability", "able"),
        )
        val encoded = json.encodeToString(WalletKeyBundle.serializer(), bundle)
        assertEquals(bundle, json.decodeFromString(WalletKeyBundle.serializer(), encoded))
    }

    @Test
    fun bundleRoundTripsThroughJsonWithoutAMnemonic() {
        val bundle = WalletDerivation.encodePlaintextBundle(privateKey, mnemonic = null)
        val encoded = json.encodeToString(WalletKeyBundle.serializer(), bundle)
        assertEquals(bundle, json.decodeFromString(WalletKeyBundle.serializer(), encoded))
    }

    /**
     * The mnemonic default keeps old ciphertexts readable: a bundle written
     * before the field existed decodes with a null mnemonic rather than failing.
     */
    @Test
    fun bundleDecodesLegacyJsonWithoutTheMnemonicField() {
        val decoded = json.decodeFromString(
            WalletKeyBundle.serializer(),
            """{"privateKeyHex":"${"11".repeat(32)}"}""",
        )
        assertEquals("11".repeat(32), decoded.privateKeyHex)
        assertNull(decoded.mnemonic)
    }
}
