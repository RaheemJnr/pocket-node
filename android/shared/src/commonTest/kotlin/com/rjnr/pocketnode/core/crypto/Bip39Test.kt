package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Known-answer tests for [Bip39] and the two primitives under it (#507).
 *
 * The BIP-39 cases are the official Trezor English vectors, all 24 of them,
 * from https://github.com/trezor/python-mnemonic/blob/master/vectors.json.
 * Their passphrase is the literal string "TREZOR". Each one pins the whole
 * chain at once: entropy -> words -> words -> entropy -> 64-byte seed.
 *
 * The HMAC cases are RFC 4231 sections 4.2 and 4.3. The PBKDF2 cases come from
 * the usual PBKDF2-HMAC-SHA512 set and were re-derived from OpenSSL (Python
 * `hashlib.pbkdf2_hmac`) before being pasted here.
 *
 * These run on the JVM and on iOS. A failure is a wallet that derives the wrong
 * key, not a flaky test: never "fix" it by editing an expectation.
 */
class Bip39Test {

    private class Vector(val entropy: String, val mnemonic: String, val seed: String)

    private val trezorVectors = listOf(
        Vector(
            entropy = "00000000000000000000000000000000",
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            seed = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
        ),
        Vector(
            entropy = "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            mnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow",
            seed = "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607",
        ),
        Vector(
            entropy = "80808080808080808080808080808080",
            mnemonic = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            seed = "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8",
        ),
        Vector(
            entropy = "ffffffffffffffffffffffffffffffff",
            mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            seed = "ac27495480225222079d7be181583751e86f571027b0497b5b5d11218e0a8a13332572917f0f8e5a589620c6f15b11c61dee327651a14c34e18231052e48c069",
        ),
        Vector(
            entropy = "000000000000000000000000000000000000000000000000",
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon agent",
            seed = "035895f2f481b1b0f01fcf8c289c794660b289981a78f8106447707fdd9666ca06da5a9a565181599b79f53b844d8a71dd9f439c52a3d7b3e8a79c906ac845fa",
        ),
        Vector(
            entropy = "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            mnemonic = "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal will",
            seed = "f2b94508732bcbacbcc020faefecfc89feafa6649a5491b8c952cede496c214a0c7b3c392d168748f2d4a612bada0753b52a1c7ac53c1e93abd5c6320b9e95dd",
        ),
        Vector(
            entropy = "808080808080808080808080808080808080808080808080",
            mnemonic = "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter always",
            seed = "107d7c02a5aa6f38c58083ff74f04c607c2d2c0ecc55501dadd72d025b751bc27fe913ffb796f841c49b1d33b610cf0e91d3aa239027f5e99fe4ce9e5088cd65",
        ),
        Vector(
            entropy = "ffffffffffffffffffffffffffffffffffffffffffffffff",
            mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo when",
            seed = "0cd6e5d827bb62eb8fc1e262254223817fd068a74b5b449cc2f667c3f1f985a76379b43348d952e2265b4cd129090758b3e3c2c49103b5051aac2eaeb890a528",
        ),
        Vector(
            entropy = "0000000000000000000000000000000000000000000000000000000000000000",
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
            seed = "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
        ),
        Vector(
            entropy = "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            mnemonic = "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title",
            seed = "bc09fca1804f7e69da93c2f2028eb238c227f2e9dda30cd63699232578480a4021b146ad717fbb7e451ce9eb835f43620bf5c514db0f8add49f5d121449d3e87",
        ),
        Vector(
            entropy = "8080808080808080808080808080808080808080808080808080808080808080",
            mnemonic = "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless",
            seed = "c0c519bd0e91a2ed54357d9d1ebef6f5af218a153624cf4f2da911a0ed8f7a09e2ef61af0aca007096df430022f7a2b6fb91661a9589097069720d015e4e982f",
        ),
        Vector(
            entropy = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
            seed = "dd48c104698c30cfe2b6142103248622fb7bb0ff692eebb00089b32d22484e1613912f0a5b694407be899ffd31ed3992c456cdf60f5d4564b8ba3f05a69890ad",
        ),
        Vector(
            entropy = "9e885d952ad362caeb4efe34a8e91bd2",
            mnemonic = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
            seed = "274ddc525802f7c828d8ef7ddbcdc5304e87ac3535913611fbbfa986d0c9e5476c91689f9c8a54fd55bd38606aa6a8595ad213d4c9c9f9aca3fb217069a41028",
        ),
        Vector(
            entropy = "6610b25967cdcca9d59875f5cb50b0ea75433311869e930b",
            mnemonic = "gravity machine north sort system female filter attitude volume fold club stay feature office ecology stable narrow fog",
            seed = "628c3827a8823298ee685db84f55caa34b5cc195a778e52d45f59bcf75aba68e4d7590e101dc414bc1bbd5737666fbbef35d1f1903953b66624f910feef245ac",
        ),
        Vector(
            entropy = "68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c",
            mnemonic = "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",
            seed = "64c87cde7e12ecf6704ab95bb1408bef047c22db4cc7491c4271d170a1b213d20b385bc1588d9c7b38f1b39d415665b8a9030c9ec653d75e65f847d8fc1fc440",
        ),
        Vector(
            entropy = "c0ba5a8e914111210f2bd131f3d5e08d",
            mnemonic = "scheme spot photo card baby mountain device kick cradle pact join borrow",
            seed = "ea725895aaae8d4c1cf682c1bfd2d358d52ed9f0f0591131b559e2724bb234fca05aa9c02c57407e04ee9dc3b454aa63fbff483a8b11de949624b9f1831a9612",
        ),
        Vector(
            entropy = "6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3",
            mnemonic = "horn tenant knee talent sponsor spell gate clip pulse soap slush warm silver nephew swap uncle crack brave",
            seed = "fd579828af3da1d32544ce4db5c73d53fc8acc4ddb1e3b251a31179cdb71e853c56d2fcb11aed39898ce6c34b10b5382772db8796e52837b54468aeb312cfc3d",
        ),
        Vector(
            entropy = "9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863",
            mnemonic = "panda eyebrow bullet gorilla call smoke muffin taste mesh discover soft ostrich alcohol speed nation flash devote level hobby quick inner drive ghost inside",
            seed = "72be8e052fc4919d2adf28d5306b5474b0069df35b02303de8c1729c9538dbb6fc2d731d5f832193cd9fb6aeecbc469594a70e3dd50811b5067f3b88b28c3e8d",
        ),
        Vector(
            entropy = "23db8160a31d3e0dca3688ed941adbf3",
            mnemonic = "cat swing flag economy stadium alone churn speed unique patch report train",
            seed = "deb5f45449e615feff5640f2e49f933ff51895de3b4381832b3139941c57b59205a42480c52175b6efcffaa58a2503887c1e8b363a707256bdd2b587b46541f5",
        ),
        Vector(
            entropy = "8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0",
            mnemonic = "light rule cinnamon wrap drastic word pride squirrel upgrade then income fatal apart sustain crack supply proud access",
            seed = "4cbdff1ca2db800fd61cae72a57475fdc6bab03e441fd63f96dabd1f183ef5b782925f00105f318309a7e9c3ea6967c7801e46c8a58082674c860a37b93eda02",
        ),
        Vector(
            entropy = "066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad",
            mnemonic = "all hour make first leader extend hole alien behind guard gospel lava path output census museum junior mass reopen famous sing advance salt reform",
            seed = "26e975ec644423f4a4c4f4215ef09b4bd7ef924e85d1d17c4cf3f136c2863cf6df0a475045652c57eb5fb41513ca2a2d67722b77e954b4b3fc11f7590449191d",
        ),
        Vector(
            entropy = "f30f8c1da665478f49b001d94c5fc452",
            mnemonic = "vessel ladder alter error federal sibling chat ability sun glass valve picture",
            seed = "2aaa9242daafcee6aa9d7269f17d4efe271e1b9a529178d7dc139cd18747090bf9d60295d0ce74309a78852a9caadf0af48aae1c6253839624076224374bc63f",
        ),
        Vector(
            entropy = "c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05",
            mnemonic = "scissors invite lock maple supreme raw rapid void congress muscle digital elegant little brisk hair mango congress clump",
            seed = "7b4a10be9d98e6cba265566db7f136718e1398c71cb581e1b2f464cac1ceedf4f3e274dc270003c670ad8d02c4558b2f8e39edea2775c9e232c7cb798b069e88",
        ),
        Vector(
            entropy = "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f",
            mnemonic = "void come effort suffer camp survey warrior heavy shoot primary clutch crush open amazing screen patrol group space point ten exist slush involve unfold",
            seed = "01f5bced59dec48e362f2c45b5de68b9fd6c92c6634f44d6d40aab69056506f0e35524a518034ddc1192e1dacd32c1ed3eaa3c3b131c88ed8e7e54c49a5d0998",
        ),
    )

    // -- Wordlist --

    @Test
    fun wordlist_has_2048_unique_words_in_sorted_order() {
        assertEquals(2048, Bip39.WORDLIST.size)
        assertEquals(2048, Bip39.WORDLIST.toSet().size)
        assertEquals(Bip39.WORDLIST.sorted(), Bip39.WORDLIST)
        assertEquals("abandon", Bip39.WORDLIST.first())
        assertEquals("zoo", Bip39.WORDLIST.last())
    }

    // -- Official BIP-39 vectors --

    @Test
    fun trezor_vectors_entropy_to_mnemonic() {
        for ((index, vector) in trezorVectors.withIndex()) {
            assertEquals(
                vector.mnemonic.split(" "),
                Bip39.entropyToMnemonic(vector.entropy.hexToByteArray()),
                "entropyToMnemonic disagrees on Trezor vector $index",
            )
        }
    }

    @Test
    fun trezor_vectors_mnemonic_to_entropy() {
        for ((index, vector) in trezorVectors.withIndex()) {
            assertEquals(
                vector.entropy,
                Bip39.mnemonicToEntropy(vector.mnemonic.split(" ")).toHexStringNoPrefix(),
                "mnemonicToEntropy disagrees on Trezor vector $index",
            )
        }
    }

    @Test
    fun trezor_vectors_mnemonic_to_seed() {
        for ((index, vector) in trezorVectors.withIndex()) {
            assertEquals(
                vector.seed,
                Bip39.toSeed(vector.mnemonic.split(" "), "TREZOR").toHexStringNoPrefix(),
                "toSeed disagrees on Trezor vector $index",
            )
        }
    }

    @Test
    fun trezor_vectors_all_validate() {
        for ((index, vector) in trezorVectors.withIndex()) {
            assertTrue(
                Bip39.validate(vector.mnemonic.split(" ")),
                "Trezor vector $index failed validation",
            )
        }
    }

    @Test
    fun trezor_vectors_cover_both_shipped_word_counts() {
        val sizes = trezorVectors.map { it.mnemonic.split(" ").size }.toSet()
        assertTrue(12 in sizes, "no 12-word vector")
        assertTrue(24 in sizes, "no 24-word vector")
    }

    // -- Empty passphrase --

    @Test
    fun toSeed_empty_passphrase_matches_bip39_reference() {
        // The canonical all-"abandon" phrase with no passphrase, as used across
        // the BIP-39 ecosystem and by MnemonicManagerTest.
        val words = ("abandon ".repeat(11) + "about").split(" ")
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            Bip39.toSeed(words).toHexStringNoPrefix(),
        )
        assertEquals(64, Bip39.toSeed(words).size)
    }

    @Test
    fun toSeed_passphrase_changes_the_seed() {
        val words = ("abandon ".repeat(11) + "about").split(" ")
        assertFalse(Bip39.toSeed(words).contentEquals(Bip39.toSeed(words, "TREZOR")))
    }

    // -- Generation --

    @Test
    fun generate_uses_only_the_supplied_entropy() {
        // A fixed source proves generate() reaches for nothing else: same bytes
        // in, same words out, and they are the words the vector predicts.
        val entropy = "00000000000000000000000000000000".hexToByteArray()
        val words = Bip39.generate(12, FixedEntropySource(entropy))
        assertEquals(("abandon ".repeat(11) + "about").split(" "), words)
    }

    @Test
    fun generate_produces_valid_mnemonics_of_the_requested_length() {
        var counter = 0
        val source = object : EntropySource {
            override fun nextBytes(n: Int): ByteArray = ByteArray(n) { (counter++ * 31 + it).toByte() }
        }
        for (wordCount in listOf(12, 24)) {
            repeat(8) {
                val words = Bip39.generate(wordCount, source)
                assertEquals(wordCount, words.size)
                assertTrue(Bip39.validate(words), "generated mnemonic failed validation")
            }
        }
    }

    @Test
    fun generate_rejects_unsupported_word_counts() {
        val source = FixedEntropySource(ByteArray(32))
        for (wordCount in listOf(0, 11, 15, 18, 21, 25, -12)) {
            assertFailsWith<IllegalArgumentException>("accepted wordCount=$wordCount") {
                Bip39.generate(wordCount, source)
            }
        }
    }

    @Test
    fun generate_rejects_a_short_entropy_source() {
        val short = object : EntropySource {
            override fun nextBytes(n: Int): ByteArray = ByteArray(n - 1)
        }
        assertFailsWith<IllegalArgumentException> { Bip39.generate(12, short) }
    }

    // -- Validation --

    @Test
    fun validate_rejects_a_word_that_is_not_on_the_list() {
        val words = ("abandon ".repeat(11) + "about").split(" ").toMutableList()
        words[0] = "zzzznotaword"
        assertFalse(Bip39.validate(words))
    }

    @Test
    fun validate_rejects_a_broken_checksum() {
        // Swapping the last word keeps every word on the list but breaks the
        // checksum, which is the only thing standing between a typo and a
        // silently different wallet.
        val words = ("abandon ".repeat(11) + "abandon").split(" ")
        assertFalse(Bip39.validate(words))
    }

    @Test
    fun validate_rejects_bad_word_counts() {
        val twelve = ("abandon ".repeat(11) + "about").split(" ")
        assertFalse(Bip39.validate(emptyList()))
        assertFalse(Bip39.validate(twelve.take(11)))
        assertFalse(Bip39.validate(twelve + "about"))
    }

    @Test
    fun mnemonicToEntropy_error_message_never_quotes_a_word() {
        val words = ("abandon ".repeat(11) + "about").split(" ").toMutableList()
        words[3] = "zzzznotaword"
        val message = assertFailsWith<IllegalArgumentException> {
            Bip39.mnemonicToEntropy(words)
        }.message.orEmpty()
        assertFalse(message.contains("zzzznotaword"), "seed words must not reach an error message")
        assertFalse(message.contains("abandon"), "seed words must not reach an error message")
    }

    @Test
    fun entropyToMnemonic_rejects_bad_entropy_lengths() {
        for (size in listOf(0, 4, 15, 17, 18, 33, 64)) {
            assertFailsWith<IllegalArgumentException>("accepted $size bytes") {
                Bip39.entropyToMnemonic(ByteArray(size))
            }
        }
    }

    @Test
    fun entropy_round_trips_at_every_legal_length() {
        for (size in listOf(16, 20, 24, 28, 32)) {
            val entropy = ByteArray(size) { (it * 7 + 3).toByte() }
            val words = Bip39.entropyToMnemonic(entropy)
            assertEquals(size * 3 / 4, words.size)
            assertTrue(Bip39.validate(words))
            assertTrue(entropy.contentEquals(Bip39.mnemonicToEntropy(words)))
        }
    }

    // -- HMAC-SHA-512, RFC 4231 --

    @Test
    fun hmacSha512_rfc4231_case_1() {
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
                "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            HmacSha512.mac(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())
                .toHexStringNoPrefix(),
        )
    }

    @Test
    fun hmacSha512_rfc4231_case_2() {
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554" +
                "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            HmacSha512.mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
                .toHexStringNoPrefix(),
        )
    }

    @Test
    fun hmacSha512_output_is_64_bytes() {
        assertEquals(64, HmacSha512.MAC_LENGTH)
        assertEquals(64, HmacSha512.mac("k".encodeToByteArray(), "m".encodeToByteArray()).size)
    }

    // -- PBKDF2-HMAC-SHA512 --

    @Test
    fun pbkdf2HmacSha512_known_vectors() {
        val password = "password".encodeToByteArray()
        val salt = "salt".encodeToByteArray()
        assertEquals(
            "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252" +
                "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            Pbkdf2.hmacSha512(password, salt, iterations = 1, keyLengthBytes = 64)
                .toHexStringNoPrefix(),
        )
        assertEquals(
            "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c" +
                "f76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e",
            Pbkdf2.hmacSha512(password, salt, iterations = 2, keyLengthBytes = 64)
                .toHexStringNoPrefix(),
        )
        assertEquals(
            "d197b1b33db0143e018b12f3d1d1479e6cdebdcc97c5c0f87f6902e072f457b5" +
                "143f30602641b3d55cd335988cb36b84376060ecd532e039b742a239434af2d5",
            Pbkdf2.hmacSha512(password, salt, iterations = 4096, keyLengthBytes = 64)
                .toHexStringNoPrefix(),
        )
    }

    @Test
    fun pbkdf2HmacSha512_spans_and_truncates_blocks() {
        // 100 bytes is two HMAC blocks with the second one cut short, the only
        // path BIP-39's fixed 64-byte output never exercises.
        assertEquals(
            "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c" +
                "f76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e" +
                "473e311ad827b68945f4e2dddb204c78e40e2495141e411cd272d020640d673c" +
                "d34aa29f",
            Pbkdf2.hmacSha512(
                "password".encodeToByteArray(),
                "salt".encodeToByteArray(),
                iterations = 2,
                keyLengthBytes = 100,
            ).toHexStringNoPrefix(),
        )
    }

    @Test
    fun pbkdf2HmacSha512_rejects_nonsense_parameters() {
        val password = "password".encodeToByteArray()
        val salt = "salt".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { Pbkdf2.hmacSha512(password, salt, 0, 64) }
        assertFailsWith<IllegalArgumentException> { Pbkdf2.hmacSha512(password, salt, -1, 64) }
        assertFailsWith<IllegalArgumentException> { Pbkdf2.hmacSha512(password, salt, 1, 0) }
    }

    private class FixedEntropySource(private val bytes: ByteArray) : EntropySource {
        override fun nextBytes(n: Int): ByteArray = bytes.copyOf(n)
    }
}
