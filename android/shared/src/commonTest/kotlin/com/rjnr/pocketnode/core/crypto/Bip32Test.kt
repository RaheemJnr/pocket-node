package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [Bip32] against the official BIP-32 test vectors, plus the CKB BIP-44 path.
 *
 * The vectors are vectors 1, 2 and 3 from
 * https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki. The spec
 * publishes each level as a base58 `xprv`; the constants below are the private
 * key and chain code those strings decode to (bytes 46..77 and 13..44 of the
 * decoded 78-byte payload). Vector 3 exists precisely to catch implementations
 * that drop a leading zero byte from the key, and its master key starts `00`.
 *
 * Vector 2 also covers indices above `2^31` in their hardened form
 * (`2147483647'`), which is where a signed 32-bit index overflows if the
 * hardened offset is added as an `Int` instead of a `Long`.
 */
class Bip32Test {

    // -- BIP-32 vector 1: seed 000102030405060708090a0b0c0d0e0f --

    private val vector1Seed = "000102030405060708090a0b0c0d0e0f".hexToByteArray()

    @Test
    fun vector1_master() {
        // Chain m
        assertLevel(
            key = Bip32.masterKeyOfAnyLength(vector1Seed),
            expectedKey = "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35",
            expectedChainCode = "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
        )
    }

    @Test
    fun vector1_allLevels() {
        // The vector's seed is 128 bits, which is legal for BIP-32 but not for
        // the app's 64-byte-only entry points, hence [masterKeyOfAnyLength].
        var key = Bip32.masterKeyOfAnyLength(vector1Seed)

        // Chain m/0'
        key = Bip32.deriveHardened(key, 0)
        assertLevel(
            key = key,
            expectedKey = "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea",
            expectedChainCode = "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141",
        )

        // Chain m/0'/1
        key = Bip32.deriveNormal(key, 1)
        assertLevel(
            key = key,
            expectedKey = "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368",
            expectedChainCode = "2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19",
        )

        // Chain m/0'/1/2'
        key = Bip32.deriveHardened(key, 2)
        assertLevel(
            key = key,
            expectedKey = "cbce0d719ecf7431d88e6a89fa1483e02e35092af60c042b1df2ff59fa424dca",
            expectedChainCode = "04466b9cc8e161e966409ca52986c584f07e9dc81f735db683c3ff6ec7b1503f",
        )

        // Chain m/0'/1/2'/2
        key = Bip32.deriveNormal(key, 2)
        assertLevel(
            key = key,
            expectedKey = "0f479245fb19a38a1954c5c7c0ebab2f9bdfd96a17563ef28a6a4b1a2a764ef4",
            expectedChainCode = "cfb71883f01676f587d023cc53a35bc7f88f724b1f8c2892ac1275ac822a3edd",
        )

        // Chain m/0'/1/2'/2/1000000000
        key = Bip32.deriveNormal(key, 1_000_000_000)
        assertLevel(
            key = key,
            expectedKey = "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8",
            expectedChainCode = "c783e67b921d2beb8f6b389cc646d7263b4145701dadd2161548a8b078e65e9e",
        )
    }

    // -- BIP-32 vector 2 --

    private val vector2Seed = (
        "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
            "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542"
        ).hexToByteArray()

    @Test
    fun vector2_allLevels() {
        var key = Bip32.masterKey(vector2Seed)

        // Chain m
        assertLevel(
            key = key,
            expectedKey = "4b03d6fc340455b363f51020ad3ecca4f0850280cf436c70c727923f6db46c3e",
            expectedChainCode = "60499f801b896d83179a4374aeb7822aaeaceaa0db1f85ee3e904c4defbd9689",
        )

        // Chain m/0
        key = Bip32.deriveNormal(key, 0)
        assertLevel(
            key = key,
            expectedKey = "abe74a98f6c7eabee0428f53798f0ab8aa1bd37873999041703c742f15ac7e1e",
            expectedChainCode = "f0909affaa7ee7abe5dd4e100598d4dc53cd709d5a5c2cac40e7412f232f7c9c",
        )

        // Chain m/0/2147483647'
        key = Bip32.deriveHardened(key, 2147483647)
        assertLevel(
            key = key,
            expectedKey = "877c779ad9687164e9c2f4f0f4ff0340814392330693ce95a58fe18fd52e6e93",
            expectedChainCode = "be17a268474a6bb9c61e1d720cf6215e2a88c5406c4aee7b38547f585c9a37d9",
        )

        // Chain m/0/2147483647'/1
        key = Bip32.deriveNormal(key, 1)
        assertLevel(
            key = key,
            expectedKey = "704addf544a06e5ee4bea37098463c23613da32020d604506da8c0518e1da4b7",
            expectedChainCode = "f366f48f1ea9f2d1d3fe958c95ca84ea18e4c4ddb9366c336c927eb246fb38cb",
        )

        // Chain m/0/2147483647'/1/2147483646'
        key = Bip32.deriveHardened(key, 2147483646)
        assertLevel(
            key = key,
            expectedKey = "f1c7c871a54a804afe328b4c83a1c33b8e5ff48f5087273f04efa83b247d6a2d",
            expectedChainCode = "637807030d55d01f9a0cb3a7839515d796bd07706386a6eddf06cc29a65a0e29",
        )

        // Chain m/0/2147483647'/1/2147483646'/2
        key = Bip32.deriveNormal(key, 2)
        assertLevel(
            key = key,
            expectedKey = "bb7d39bdb83ecf58f2fd82b6d918341cbef428661ef01ab97c28a4842125ac23",
            expectedChainCode = "9452b549be8cea3ecb7a84bec10dcfd94afe4d129ebfd3b3cb58eedf394ed271",
        )
    }

    @Test
    fun vector2_derivePath_matchesStepwiseDerivation() {
        var key = Bip32.masterKey(vector2Seed)
        key = Bip32.deriveNormal(key, 0)
        key = Bip32.deriveHardened(key, 2147483647)
        val stepwise = key.key.toHexStringNoPrefix()

        assertEquals(
            stepwise,
            Bip32.derivePath(vector2Seed, "m/0/2147483647'").toHexStringNoPrefix(),
        )
    }

    @Test
    fun vector2_derivePath_parsesHardenedSuffixes() {
        val expected = "bb7d39bdb83ecf58f2fd82b6d918341cbef428661ef01ab97c28a4842125ac23"
        assertEquals(
            expected,
            Bip32.derivePath(vector2Seed, "m/0/2147483647'/1/2147483646'/2").toHexStringNoPrefix(),
        )
        // `h` and `H` are the usual ASCII-safe spellings of the `'` suffix.
        assertEquals(
            expected,
            Bip32.derivePath(vector2Seed, "m/0/2147483647h/1/2147483646H/2").toHexStringNoPrefix(),
        )
    }

    // -- BIP-32 vector 3: retention of a leading zero byte --

    private val vector3Seed = (
        "4b381541583be4423346c643850da4b320e46a87ae3d2a4e6da11eba819cd4ac" +
            "ba45d239319ac14f863b8d5ab5a0d0c64d2e8a1e7d1457df2e5a3c51c73235be"
        ).hexToByteArray()

    @Test
    fun vector3_leadingZeroKeyIsNotTruncated() {
        var key = Bip32.masterKey(vector3Seed)

        // Chain m. The key is 31 significant bytes, left-padded to 32.
        assertLevel(
            key = key,
            expectedKey = "00ddb80b067e0d4993197fe10f2657a844a384589847602d56f0c629c81aae32",
            expectedChainCode = "01d28a3e53cffa419ec122c968b3259e16b65076495494d97cae10bbfec3c36f",
        )

        // Chain m/0'
        key = Bip32.deriveHardened(key, 0)
        assertLevel(
            key = key,
            expectedKey = "491f7a2eebc7b57028e0d3faa0acda02e75c33b03c48fb288c41e2ea44e1daef",
            expectedChainCode = "e5fea12a97b927fc9dc3d2cb0d1ea1cf50aa5a1fdc1f933e8906bb38df3377bd",
        )
    }

    // -- CKB BIP-44 path --

    /**
     * A fixed non-secret seed: bytes 0x00..0x3f. Never use it for a real wallet.
     */
    private val ckbSeed = (
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
        ).hexToByteArray()

    /**
     * Pins `m/44'/309'/account'/chain/index` for [ckbSeed].
     *
     * The expected keys were produced by the pre-#508 Android implementation
     * (`MnemonicManager.derivePrivateKey` on BouncyCastle HMAC and
     * `java.math.BigInteger`). `Bip32DifferentialTest` re-asserts these exact
     * three constants against a verbatim copy of that code, so a change here
     * means shipped wallets would derive different keys.
     */
    @Test
    fun ckbPath_pinsShippedKeys() {
        assertEquals(
            "3085e9a4de8b62c9a89f6f8ac6c087dc2da76591823ffcd5965a74837fcad68c",
            Bip32.deriveCkbPrivateKey(ckbSeed, 0, 0, 0).toHexStringNoPrefix(),
        )
        assertEquals(
            "6e67f6184d29488f7c2299d1c58c591f6482140100c017371054c170edf32259",
            Bip32.deriveCkbPrivateKey(ckbSeed, 0, 1, 0).toHexStringNoPrefix(),
        )
        assertEquals(
            "787cea3b7f3be5932e3ec2f8f6374f8d12dc50c5de046d3dc4093466548b7863",
            Bip32.deriveCkbPrivateKey(ckbSeed, 1, 0, 5).toHexStringNoPrefix(),
        )
    }

    @Test
    fun ckbPath_defaultsToAccountZeroReceivingChainAddressZero() {
        assertEquals(
            Bip32.deriveCkbPrivateKey(ckbSeed, 0, 0, 0).toHexStringNoPrefix(),
            Bip32.deriveCkbPrivateKey(ckbSeed).toHexStringNoPrefix(),
        )
    }

    @Test
    fun ckbPath_matchesTheEquivalentTextualPath() {
        assertEquals(
            Bip32.derivePath(ckbSeed, "m/44'/309'/1'/0/5").toHexStringNoPrefix(),
            Bip32.deriveCkbPrivateKey(ckbSeed, 1, 0, 5).toHexStringNoPrefix(),
        )
    }

    @Test
    fun derivedKeysAreValidScalarsAndDistinctPerLevel() {
        val keys = buildList {
            for (account in 0..2) {
                for (chain in 0..1) {
                    for (address in 0..2) {
                        add(Bip32.deriveCkbPrivateKey(ckbSeed, account, chain, address))
                    }
                }
            }
        }
        keys.forEach { assertEquals(32, it.size) }
        // Every derived key must produce a public key, i.e. be a valid scalar.
        keys.forEach { assertEquals(33, Secp256k1Signer.publicKey(it).size) }
        assertEquals(keys.size, keys.map { it.toHexStringNoPrefix() }.toSet().size)
    }

    // -- failure modes --

    @Test
    fun masterKey_rejectsSeedThatIsNot64Bytes() {
        val short = assertFailsWith<IllegalArgumentException> { Bip32.masterKey(ByteArray(32)) }
        assertEquals("Seed must be 64 bytes", short.message)

        val long = assertFailsWith<IllegalArgumentException> { Bip32.masterKey(ByteArray(65)) }
        assertEquals("Seed must be 64 bytes", long.message)
    }

    @Test
    fun deriveCkbPrivateKey_rejectsSeedThatIsNot64Bytes() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Bip32.deriveCkbPrivateKey(ByteArray(16))
        }
        assertEquals("Seed must be 64 bytes", failure.message)
    }

    @Test
    fun derivePath_rejectsMalformedPaths() {
        listOf(
            "",
            "44'/309'/0'/0/0", // no leading m
            "M/0", // public derivation is not supported
            "m/",
            "m/0/",
            "m/abc",
            "m/-1",
            "m/0x10",
            "m/'",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>("expected \"$path\" to be rejected") {
                Bip32.derivePath(ckbSeed, path)
            }
        }
    }

    @Test
    fun derivePath_masterOnlyReturnsTheMasterKey() {
        assertEquals(
            Bip32.masterKey(ckbSeed).key.toHexStringNoPrefix(),
            Bip32.derivePath(ckbSeed, "m").toHexStringNoPrefix(),
        )
    }

    @Test
    fun deriveChild_rejectsNegativeIndex() {
        val master = Bip32.masterKey(ckbSeed)
        assertFailsWith<IllegalArgumentException> { Bip32.deriveHardened(master, -1) }
        assertFailsWith<IllegalArgumentException> { Bip32.deriveNormal(master, -1) }
    }

    @Test
    fun extendedPrivateKey_rejectsWrongSizedComponents() {
        assertFailsWith<IllegalArgumentException> {
            Bip32.ExtendedPrivateKey(ByteArray(31), ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            Bip32.ExtendedPrivateKey(ByteArray(32), ByteArray(33))
        }
    }

    // -- helpers --

    private fun assertLevel(
        key: Bip32.ExtendedPrivateKey,
        expectedKey: String,
        expectedChainCode: String,
    ) {
        assertEquals(expectedKey, key.key.toHexStringNoPrefix())
        assertEquals(expectedChainCode, key.chainCode.toHexStringNoPrefix())
    }
}
