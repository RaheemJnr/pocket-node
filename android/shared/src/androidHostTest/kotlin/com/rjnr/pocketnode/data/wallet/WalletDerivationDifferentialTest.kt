package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.crypto.toHexString
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.nervos.ckb.Network
import org.nervos.ckb.crypto.secp256k1.ECKeyPair
import org.nervos.ckb.utils.Numeric
import org.nervos.ckb.utils.address.Address
import org.nervos.ckb.crypto.Blake2b as SdkBlake2b
import org.nervos.ckb.type.Script as SdkScript

/**
 * Differential tests: shared wallet derivation against the CKB Java SDK it
 * replaced (#511, on the #454 pattern).
 *
 * The SDK is a TEST-ONLY dependency of this source set — it must never appear on
 * a production classpath (:app's `checkNoCkbSdkOnRuntimeClasspath` enforces
 * that). A disagreement here means a wallet would derive a different lock script
 * and therefore a different address, stranding funds: it is a derivation bug,
 * not a test bug. Do not relax an assertion to make it pass.
 *
 * Assertion messages never carry key bytes; see [keyFingerprint].
 */
class WalletDerivationDifferentialTest {

    private companion object {
        const val SEED = 20260915L
        const val ITERATIONS = 500

        /** secp256k1 group order. */
        val CURVE_N: BigInteger = BigInteger(
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16,
        )

        val SECP256K1_CODE_HASH: ByteArray = Numeric.hexStringToByteArray(
            "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        )
    }

    /**
     * A short, non-reversible label for a private key. Assertion messages end up
     * in CI logs and JUnit XML, so they must never carry key bytes. The seeded
     * iteration index already reproduces any failure exactly.
     */
    private fun keyFingerprint(key: ByteArray): String =
        com.rjnr.pocketnode.core.crypto.Blake2b.digest(key).copyOfRange(0, 8).toHexString()

    /** A uniformly random private key in [1, n-1]. */
    private fun Random.privateKey(): ByteArray {
        while (true) {
            val candidate = nextBytes(32)
            val value = BigInteger(1, candidate)
            if (value.signum() > 0 && value < CURVE_N) return candidate
        }
    }

    /** The SDK's lock script for [privateKey], built the way the SDK docs do it. */
    private fun sdkScript(privateKey: ByteArray): SdkScript {
        val publicKey = ECKeyPair.create(BigInteger(1, privateKey)).getEncodedPublicKey(true)
        val args = SdkBlake2b.digest(publicKey).copyOfRange(0, 20)
        return SdkScript(SECP256K1_CODE_HASH, args, SdkScript.HashType.TYPE)
    }

    @Test
    fun lockScriptMatchesTheSdkOverRandomKeys() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val key = random.privateKey()
            val sdk = sdkScript(key)
            val ours = WalletDerivation.lockScript(WalletDerivation.publicKey(key))

            assertEquals(
                Numeric.toHexString(sdk.args),
                ours.args,
                "lock args mismatch at iteration $i for key ${keyFingerprint(key)}",
            )
            assertEquals(
                Numeric.toHexString(sdk.codeHash),
                ours.codeHash,
                "code hash mismatch at iteration $i for key ${keyFingerprint(key)}",
            )
            assertEquals("type", ours.hashType, "hash type mismatch at iteration $i")
        }
    }

    @Test
    fun bothAddressesMatchTheSdkOverRandomKeys() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val key = random.privateKey()
            val sdk = sdkScript(key)
            val info = WalletDerivation.walletInfo(key)

            assertEquals(
                Address(sdk, Network.TESTNET).encode(),
                info.testnetAddress,
                "testnet address mismatch at iteration $i for key ${keyFingerprint(key)}",
            )
            assertEquals(
                Address(sdk, Network.MAINNET).encode(),
                info.mainnetAddress,
                "mainnet address mismatch at iteration $i for key ${keyFingerprint(key)}",
            )
        }
    }

    @Test
    fun publicKeyMatchesTheSdkOverRandomKeys() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val key = random.privateKey()
            val sdk = ECKeyPair.create(BigInteger(1, key)).getEncodedPublicKey(true)
            assertEquals(
                Numeric.toHexString(sdk),
                WalletDerivation.walletInfo(key).publicKey,
                "public key mismatch at iteration $i for key ${keyFingerprint(key)}",
            )
        }
    }

    /**
     * The address-only path must recover exactly the script the key-based path
     * produced, on both networks. This is what boot and wallet switch rely on
     * for auth-bound wallets whose key cannot be read without a prompt.
     */
    @Test
    fun walletInfoFromAddressesRecoversTheSdkScript() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val key = random.privateKey()
            val sdk = sdkScript(key)
            val testnet = Address(sdk, Network.TESTNET).encode()
            val mainnet = Address(sdk, Network.MAINNET).encode()

            for (recovered in listOf(
                WalletDerivation.walletInfoFromAddresses(testnet, mainnet),
                WalletDerivation.walletInfoFromAddresses("", mainnet),
            )) {
                assertEquals(
                    Numeric.toHexString(sdk.args),
                    recovered.script.args,
                    "recovered args mismatch at iteration $i for key ${keyFingerprint(key)}",
                )
                assertEquals("type", recovered.script.hashType)
                assertEquals("", recovered.publicKey)
            }
        }
    }

    /** Also emits the vectors pinned in the commonTest suite; see its KDoc. */
    @Test
    fun pinnedVectorMatchesTheSdk() {
        val key = Numeric.hexStringToByteArray(
            "0x1111111111111111111111111111111111111111111111111111111111111111",
        )
        val sdk = sdkScript(key)
        val info = WalletDerivation.walletInfo(key)

        assertEquals(Numeric.toHexString(sdk.args), info.script.args)
        assertEquals(Address(sdk, Network.TESTNET).encode(), info.testnetAddress)
        assertEquals(Address(sdk, Network.MAINNET).encode(), info.mainnetAddress)
        assertEquals(
            info.testnetAddress,
            AddressUtils.encode(info.script, NetworkType.TESTNET),
        )

        println("PINNED_PUB=${info.publicKey}")
        println("PINNED_ARGS=${info.script.args}")
        println("PINNED_CKT=${info.testnetAddress}")
        println("PINNED_CKB=${info.mainnetAddress}")
    }
}
