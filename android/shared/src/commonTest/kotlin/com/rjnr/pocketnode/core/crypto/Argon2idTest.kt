package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Known-answer tests for [Argon2id].
 *
 * The first vector is the one published in RFC 9106 section 5.3, the only Argon2id
 * vector in the RFC and the only one that exercises the secret and associated-data
 * inputs. The rest come from the reference `argon2` CLI (`brew install argon2`,
 * version 20190702), with the exact command recorded above each one. Note that the
 * CLI's `-m` is a log2 exponent: `-m 16` means 2^16 KiB, that is 64 MiB.
 */
class Argon2idTest {

    /**
     * RFC 9106 section 5.3 "Argon2id Test Vectors":
     * t = 3, m = 32 KiB, p = 4, tag 32 bytes, password 0x01 x 32, salt 0x02 x 16,
     * secret 0x03 x 8, associated data 0x04 x 12.
     */
    @Test
    fun `RFC 9106 section 5 3 Argon2id vector`() {
        val tag = Argon2id.hash(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            params = Argon2id.Params(iterations = 3, memoryKib = 32, parallelism = 4, tagLength = 32),
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
        )

        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            tag.toHexStringNoPrefix(),
        )
    }

    /** `printf password | argon2 somesalt -id -t 2 -m 16 -p 1 -l 32 -r` (m = 64 MiB). */
    @Test
    fun `reference CLI vector t2 m64MiB p1`() {
        val tag = Argon2id.hash(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            params = Argon2id.Params(
                iterations = 2,
                memoryKib = 64 * 1024,
                parallelism = 1,
                tagLength = 32,
            ),
        )

        assertEquals(
            "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
            tag.toHexStringNoPrefix(),
        )
    }

    /**
     * `printf password | argon2 somesalt -id -t 3 -m 16 -p 4 -l 32 -r` (m = 64 MiB).
     *
     * These are exactly the production PIN parameters, so this vector is the one that
     * proves an Android PIN hash and an iOS PIN hash agree.
     */
    @Test
    fun `reference CLI vector at the production PIN parameters`() {
        val tag = Argon2id.hash(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            params = Argon2id.Params(
                iterations = 3,
                memoryKib = 64 * 1024,
                parallelism = 4,
                tagLength = 32,
            ),
        )

        assertEquals(
            "661fefbd6f29bcbc8f4646abc32a9d7a4645bb5c059537f8a5587f31adbecccd",
            tag.toHexStringNoPrefix(),
        )
    }

    /** `printf 'pocket-node' | argon2 abcdefghij0123456789 -id -t 1 -m 8 -p 2 -l 64 -r`. */
    @Test
    fun `reference CLI vector t1 m256KiB p2 with a 64 byte tag`() {
        val tag = Argon2id.hash(
            password = "pocket-node".encodeToByteArray(),
            salt = "abcdefghij0123456789".encodeToByteArray(),
            params = Argon2id.Params(
                iterations = 1,
                memoryKib = 256,
                parallelism = 2,
                tagLength = 64,
            ),
        )

        assertEquals(
            "e7002073785927575d2b665868075fe60e66e694b623ee6e72e6b0eb4f1ec45a" +
                "b9be7454f1d57d080cfead6347eadabb67b65f413cad33cb8007e509b2432f2c",
            tag.toHexStringNoPrefix(),
        )
    }

    /**
     * `printf 'pocket-node' | argon2 abcdefghij0123456789 -id -t 2 -m 10 -p 3 -l 100 -r`.
     *
     * A 100-byte tag takes H' down its chained branch (more than 64 bytes of output), which
     * the 32-byte production tag never touches.
     */
    @Test
    fun `reference CLI vector with a 100 byte tag`() {
        val tag = Argon2id.hash(
            password = "pocket-node".encodeToByteArray(),
            salt = "abcdefghij0123456789".encodeToByteArray(),
            params = Argon2id.Params(
                iterations = 2,
                memoryKib = 1024,
                parallelism = 3,
                tagLength = 100,
            ),
        )

        assertEquals(
            "7b239f42e03dd66e36b5d52babf82a06df45d3477deddad38c6392e71b8eaba2" +
                "10722aad67bbeed6e02966bd7533d5bb8960767c8743057a6154ba66ee71f530" +
                "3bcf5c727736f9373eb9ea8543eca309831577a986060794c7bf61df96408119" +
                "aecf9314",
            tag.toHexStringNoPrefix(),
        )
    }

    @Test
    fun `an empty password is accepted`() {
        val tag = Argon2id.hash(
            password = ByteArray(0),
            salt = "abcdefghij0123456789".encodeToByteArray(),
            params = Argon2id.Params(iterations = 1, memoryKib = 64, parallelism = 1),
        )

        assertEquals(32, tag.size)
    }

    @Test
    fun `hashing the same input twice is deterministic`() {
        val password = "correct horse battery staple".encodeToByteArray()
        val salt = ByteArray(16) { it.toByte() }
        val params = Argon2id.Params(iterations = 2, memoryKib = 512, parallelism = 2)

        assertContentEquals(
            Argon2id.hash(password, salt, params),
            Argon2id.hash(password, salt, params),
        )
    }

    @Test
    fun `a different salt produces a different tag`() {
        val password = "123456".encodeToByteArray()
        val params = Argon2id.Params(iterations = 1, memoryKib = 64, parallelism = 1)

        val a = Argon2id.hash(password, ByteArray(16) { 0x11 }, params)
        val b = Argon2id.hash(password, ByteArray(16) { 0x12 }, params)

        assertEquals(false, a.contentEquals(b))
    }

    // -- Parameter validation ---------------------------------------------

    @Test
    fun `a salt shorter than 8 bytes is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2id.hash(
                password = "x".encodeToByteArray(),
                salt = ByteArray(7),
                params = Argon2id.Params(iterations = 1, memoryKib = 64, parallelism = 1),
            )
        }
    }

    @Test
    fun `memory below 8 times parallelism is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2id.hash(
                password = "x".encodeToByteArray(),
                salt = ByteArray(16),
                params = Argon2id.Params(iterations = 1, memoryKib = 31, parallelism = 4),
            )
        }
    }

    @Test
    fun `memory exactly 8 times parallelism is accepted`() {
        val tag = Argon2id.hash(
            password = "x".encodeToByteArray(),
            salt = ByteArray(16),
            params = Argon2id.Params(iterations = 1, memoryKib = 32, parallelism = 4),
        )

        assertEquals(32, tag.size)
    }

    @Test
    fun `parallelism outside 1 to 255 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2id.hash(
                password = "x".encodeToByteArray(),
                salt = ByteArray(16),
                params = Argon2id.Params(iterations = 1, memoryKib = 64, parallelism = 0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Argon2id.hash(
                password = "x".encodeToByteArray(),
                salt = ByteArray(16),
                params = Argon2id.Params(iterations = 1, memoryKib = 4096, parallelism = 256),
            )
        }
    }

    @Test
    fun `zero iterations is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2id.hash(
                password = "x".encodeToByteArray(),
                salt = ByteArray(16),
                params = Argon2id.Params(iterations = 0, memoryKib = 64, parallelism = 1),
            )
        }
    }

    @Test
    fun `a tag shorter than 4 bytes is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2id.hash(
                password = "x".encodeToByteArray(),
                salt = ByteArray(16),
                params = Argon2id.Params(
                    iterations = 1,
                    memoryKib = 64,
                    parallelism = 1,
                    tagLength = 3,
                ),
            )
        }
    }
}
