package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.crypto.Blake2b
import com.rjnr.pocketnode.core.crypto.Secp256k1Signer
import com.rjnr.pocketnode.core.crypto.toHexString
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script

/**
 * Pure wallet derivation: private key to public key to secp256k1-blake160 lock
 * script to mainnet and testnet addresses, plus the plaintext key-bundle codec.
 *
 * Extracted from the Android `KeyManager` (#511) so iOS onboarding derives the
 * same values from the same code. Nothing here touches storage, the Keystore or
 * any platform API; `KeyManager` keeps all of that and delegates here.
 *
 * Byte-for-byte compatible with what `KeyManager` produced before the move:
 * lock args and public key are `0x`-prefixed lowercase hex, and the bundle's
 * `privateKeyHex` is lowercase hex with NO prefix.
 */
object WalletDerivation {

    /** blake160 = the first 20 bytes of blake2b-256, the secp256k1 lock's args. */
    private const val BLAKE160_LENGTH = 20

    /** The 33-byte compressed public key for [privateKey]. */
    fun publicKey(privateKey: ByteArray): ByteArray = Secp256k1Signer.publicKey(privateKey)

    /** The secp256k1-blake160 lock script for a compressed [publicKey]. */
    fun lockScript(publicKey: ByteArray): Script {
        val args = Blake2b.digest(publicKey).copyOfRange(0, BLAKE160_LENGTH)
        return Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = args.toHexString()
        )
    }

    /**
     * Recovers a lock script from a CKB address without touching key material.
     *
     * The bech32m address encodes the same args + codeHash + hashType triple
     * [lockScript] produces, so the round trip is exact.
     */
    fun lockScriptFromAddress(address: String): Script = AddressUtils.decode(address)

    /** Full [WalletInfo] derived from a raw private key. */
    fun walletInfo(privateKey: ByteArray): WalletInfo {
        val publicKey = publicKey(privateKey)
        val script = lockScript(publicKey)
        return WalletInfo(
            publicKey = publicKey.toHexString(),
            script = script,
            testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET),
            mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET)
        )
    }

    /**
     * [WalletInfo] from cached addresses only. No key access, so it is safe on
     * app boot, wallet switch and multi-wallet sync setup for auth-bound (V2)
     * wallets, whose key read would otherwise require a BiometricPrompt.
     *
     * `publicKey` comes back empty: an address cannot yield the public key back.
     * The script is decoded from [testnetAddress], falling back to
     * [mainnetAddress] when the testnet one is blank.
     */
    fun walletInfoFromAddresses(testnetAddress: String, mainnetAddress: String): WalletInfo {
        val script = lockScriptFromAddress(testnetAddress.ifBlank { mainnetAddress })
        return WalletInfo(
            publicKey = "",
            script = script,
            testnetAddress = testnetAddress,
            mainnetAddress = mainnetAddress
        )
    }

    /**
     * Builds the plaintext bundle the V2 write path persists. The caller owns
     * persistence, and owns wiping [privateKey] afterwards.
     */
    fun encodePlaintextBundle(privateKey: ByteArray, mnemonic: List<String>?): WalletKeyBundle =
        WalletKeyBundle(
            privateKeyHex = privateKey.toHexStringNoPrefix(),
            mnemonic = mnemonic?.joinToString(" "),
        )
}
