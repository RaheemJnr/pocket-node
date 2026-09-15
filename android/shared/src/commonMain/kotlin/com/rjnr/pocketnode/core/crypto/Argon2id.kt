package com.rjnr.pocketnode.core.crypto

import org.kotlincrypto.hash.blake2.BLAKE2b

/**
 * Argon2id (version 0x13) per RFC 9106, in pure Kotlin for `commonMain`.
 *
 * Replaces BouncyCastle's `Argon2BytesGenerator`, a JVM-only dependency, so the PIN
 * KDF is identical on Android and iOS (#509). The only primitive it leans on is
 * KotlinCrypto's multiplatform [BLAKE2b], which supports the arbitrary digest
 * lengths Argon2's variable-length hash H' needs.
 *
 * Lanes are filled sequentially rather than in parallel threads. Argon2 is defined so
 * that the lanes of a slice never reference each other's *current* segment, so a
 * sequential fill produces a bit-identical tag to any threaded implementation. The
 * `parallelism` parameter still changes the output, it is part of the KDF, it is just
 * not a threading hint here. A differential test against BouncyCastle over 200 random
 * parameter sets keeps that honest.
 *
 * This is a pure function: it holds no state between calls and zeroes its working
 * memory, H0 and the final block before returning.
 */
object Argon2id {

    /**
     * @param iterations the time cost `t`, at least 1.
     * @param memoryKib the memory cost `m` in kibibytes, at least `8 * parallelism`.
     *   Rounded down internally to `4 * p * floor(m / 4p)` blocks, as the spec requires.
     * @param parallelism the number of lanes `p`, 1..255.
     * @param tagLength the output length in bytes, at least 4.
     */
    data class Params(
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val tagLength: Int = 32,
    )

    /**
     * Derives a [Params.tagLength]-byte tag from [password] and [salt].
     *
     * @param secret the optional key `K` (RFC 9106 calls it the "secret value").
     * @param associatedData the optional associated data `X`.
     */
    fun hash(
        password: ByteArray,
        salt: ByteArray,
        params: Params,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(params.parallelism in 1..MAX_PARALLELISM) {
            "parallelism must be in 1..$MAX_PARALLELISM, was ${params.parallelism}"
        }
        require(params.iterations >= 1) {
            "iterations must be at least 1, was ${params.iterations}"
        }
        require(params.tagLength >= MIN_TAG_LENGTH) {
            "tagLength must be at least $MIN_TAG_LENGTH bytes, was ${params.tagLength}"
        }
        require(salt.size >= MIN_SALT_LENGTH) {
            "salt must be at least $MIN_SALT_LENGTH bytes, was ${salt.size}"
        }
        require(params.memoryKib >= 8 * params.parallelism) {
            "memoryKib must be at least 8 * parallelism (${8 * params.parallelism}), " +
                "was ${params.memoryKib}"
        }
        require(params.memoryKib <= MAX_MEMORY_KIB) {
            "memoryKib must be at most $MAX_MEMORY_KIB, was ${params.memoryKib}"
        }

        val lanes = params.parallelism
        // m' = 4 * p * floor(m / 4p): the block count, rounded down to a whole number
        // of segments per lane (RFC 9106 section 3.2).
        val blockCount = 4 * lanes * (params.memoryKib / (4 * lanes))
        val laneLength = blockCount / lanes
        val segmentLength = laneLength / SYNC_POINTS

        val h0 = computeH0(password, salt, secret, associatedData, params)

        val memory = LongArray(blockCount * WORDS_PER_BLOCK)

        // B[i][0] and B[i][1] = H'^1024(H0 || LE32(index) || LE32(lane)).
        val initInput = ByteArray(H0_LENGTH + 8)
        h0.copyInto(initInput, 0)
        val blockBytes = ByteArray(BLOCK_BYTES)
        for (lane in 0 until lanes) {
            for (index in 0..1) {
                writeLe32(initInput, H0_LENGTH, index)
                writeLe32(initInput, H0_LENGTH + 4, lane)
                hPrime(blockBytes, initInput)
                loadBlock(memory, (lane * laneLength + index) * WORDS_PER_BLOCK, blockBytes)
            }
        }

        val blockR = LongArray(WORDS_PER_BLOCK)
        val blockTmp = LongArray(WORDS_PER_BLOCK)
        val zeroBlock = LongArray(WORDS_PER_BLOCK)
        val inputBlock = LongArray(WORDS_PER_BLOCK)
        val addressBlock = LongArray(WORDS_PER_BLOCK)

        for (pass in 0 until params.iterations) {
            for (slice in 0 until SYNC_POINTS) {
                for (lane in 0 until lanes) {
                    fillSegment(
                        memory = memory,
                        pass = pass,
                        slice = slice,
                        lane = lane,
                        lanes = lanes,
                        blockCount = blockCount,
                        laneLength = laneLength,
                        segmentLength = segmentLength,
                        iterations = params.iterations,
                        blockR = blockR,
                        blockTmp = blockTmp,
                        zeroBlock = zeroBlock,
                        inputBlock = inputBlock,
                        addressBlock = addressBlock,
                    )
                }
            }
        }

        // C = B[0][q-1] xor B[1][q-1] xor ... xor B[p-1][q-1]; Tag = H'^T(C).
        val finalBlock = LongArray(WORDS_PER_BLOCK)
        for (lane in 0 until lanes) {
            val offset = (lane * laneLength + laneLength - 1) * WORDS_PER_BLOCK
            for (i in 0 until WORDS_PER_BLOCK) {
                finalBlock[i] = finalBlock[i] xor memory[offset + i]
            }
        }
        storeBlock(blockBytes, finalBlock, 0)
        val tag = ByteArray(params.tagLength)
        hPrime(tag, blockBytes)

        memory.fill(0L)
        h0.fill(0)
        initInput.fill(0)
        blockBytes.fill(0)
        finalBlock.fill(0L)
        blockR.fill(0L)
        blockTmp.fill(0L)
        inputBlock.fill(0L)
        addressBlock.fill(0L)

        return tag
    }

    // -- H0 and H' ---------------------------------------------------------

    /** H0 = BLAKE2b-512 over the LE32-prefixed parameters and inputs (section 3.2). */
    private fun computeH0(
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        associatedData: ByteArray?,
        params: Params,
    ): ByteArray {
        val digest = BLAKE2b(H0_LENGTH * 8)
        digest.update(le32(params.parallelism))
        digest.update(le32(params.tagLength))
        digest.update(le32(params.memoryKib))
        digest.update(le32(params.iterations))
        digest.update(le32(VERSION))
        digest.update(le32(TYPE_ARGON2ID))
        digest.update(le32(password.size))
        if (password.isNotEmpty()) digest.update(password)
        digest.update(le32(salt.size))
        if (salt.isNotEmpty()) digest.update(salt)
        digest.update(le32(secret?.size ?: 0))
        if (secret != null && secret.isNotEmpty()) digest.update(secret)
        digest.update(le32(associatedData?.size ?: 0))
        if (associatedData != null && associatedData.isNotEmpty()) digest.update(associatedData)
        return digest.digest()
    }

    /**
     * The variable-length hash H' of RFC 9106 section 3.3, written into [out].
     *
     * Up to 64 bytes it is a single BLAKE2b of `LE32(T) || A`. Beyond that it is a
     * chain of 64-byte BLAKE2b digests whose leading 32 bytes are concatenated, with
     * a final digest of `T - 32r` bytes.
     */
    private fun hPrime(out: ByteArray, input: ByteArray) {
        val length = out.size
        if (length <= H0_LENGTH) {
            val digest = BLAKE2b(length * 8)
            digest.update(le32(length))
            digest.update(input)
            digest.digest().copyInto(out)
            return
        }
        // r = ceil(T / 32) - 2 full 32-byte chunks, then a final chunk of T - 32r bytes.
        val chunks = (length + 31) / 32 - 2
        var v = BLAKE2b(H0_LENGTH * 8).let {
            it.update(le32(length))
            it.update(input)
            it.digest()
        }
        v.copyInto(out, 0, 0, 32)
        var written = 32
        for (i in 2..chunks) {
            v = BLAKE2b(H0_LENGTH * 8).let {
                it.update(v)
                it.digest()
            }
            v.copyInto(out, written, 0, 32)
            written += 32
        }
        val remaining = length - 32 * chunks
        val last = BLAKE2b(remaining * 8).let {
            it.update(v)
            it.digest()
        }
        last.copyInto(out, written)
    }

    // -- Filling -----------------------------------------------------------

    private fun fillSegment(
        memory: LongArray,
        pass: Int,
        slice: Int,
        lane: Int,
        lanes: Int,
        blockCount: Int,
        laneLength: Int,
        segmentLength: Int,
        iterations: Int,
        blockR: LongArray,
        blockTmp: LongArray,
        zeroBlock: LongArray,
        inputBlock: LongArray,
        addressBlock: LongArray,
    ) {
        // Argon2id uses data-independent (Argon2i) addressing for the first half of the
        // first pass and data-dependent (Argon2d) addressing from there on.
        val dataIndependent = pass == 0 && slice < SYNC_POINTS / 2

        if (dataIndependent) {
            zeroBlock.fill(0L)
            inputBlock.fill(0L)
            inputBlock[0] = pass.toLong()
            inputBlock[1] = lane.toLong()
            inputBlock[2] = slice.toLong()
            inputBlock[3] = blockCount.toLong()
            inputBlock[4] = iterations.toLong()
            inputBlock[5] = TYPE_ARGON2ID.toLong()
        }

        var startingIndex = 0
        if (pass == 0 && slice == 0) {
            // B[lane][0] and B[lane][1] are already filled from H0.
            startingIndex = 2
            if (dataIndependent) {
                nextAddresses(addressBlock, inputBlock, zeroBlock, blockR, blockTmp)
            }
        }

        var currOffset = lane * laneLength + slice * segmentLength + startingIndex
        var prevOffset = if (currOffset % laneLength == 0) {
            currOffset + laneLength - 1
        } else {
            currOffset - 1
        }

        for (i in startingIndex until segmentLength) {
            if (currOffset % laneLength == 1) prevOffset = currOffset - 1

            val pseudoRand: Long = if (dataIndependent) {
                if (i % ADDRESSES_IN_BLOCK == 0) {
                    nextAddresses(addressBlock, inputBlock, zeroBlock, blockR, blockTmp)
                }
                addressBlock[i % ADDRESSES_IN_BLOCK]
            } else {
                memory[prevOffset * WORDS_PER_BLOCK]
            }

            // J2 (the high 32 bits) picks the lane, J1 (the low 32) the index within it.
            var refLane = ((pseudoRand ushr 32) % lanes).toInt()
            if (pass == 0 && slice == 0) refLane = lane

            val refIndex = indexAlpha(
                pass = pass,
                slice = slice,
                index = i,
                pseudoRand = pseudoRand and 0xFFFFFFFFL,
                sameLane = refLane == lane,
                laneLength = laneLength,
                segmentLength = segmentLength,
            )

            fillBlock(
                prev = memory, prevOff = prevOffset * WORDS_PER_BLOCK,
                ref = memory, refOff = (laneLength * refLane + refIndex) * WORDS_PER_BLOCK,
                next = memory, nextOff = currOffset * WORDS_PER_BLOCK,
                // Version 0x13 XORs the new block over the old one from the second pass on.
                withXor = pass != 0,
                blockR = blockR, blockTmp = blockTmp,
            )

            currOffset++
            prevOffset++
        }
    }

    /** Refreshes the Argon2i address block: two compressions of the counter block. */
    private fun nextAddresses(
        addressBlock: LongArray,
        inputBlock: LongArray,
        zeroBlock: LongArray,
        blockR: LongArray,
        blockTmp: LongArray,
    ) {
        inputBlock[6]++
        fillBlock(zeroBlock, 0, inputBlock, 0, addressBlock, 0, false, blockR, blockTmp)
        fillBlock(zeroBlock, 0, addressBlock, 0, addressBlock, 0, false, blockR, blockTmp)
    }

    /**
     * Maps `J1` onto an absolute block index within the reference lane (section 3.4.1.2).
     *
     * The multiplications are 64-bit unsigned: both factors are below 2^32 so the product
     * fits, but it routinely exceeds [Long.MAX_VALUE] and the shift has to be logical,
     * hence [ULong].
     */
    private fun indexAlpha(
        pass: Int,
        slice: Int,
        index: Int,
        pseudoRand: Long,
        sameLane: Boolean,
        laneLength: Int,
        segmentLength: Int,
    ): Int {
        val referenceAreaSize: Long = if (pass == 0) {
            when {
                slice == 0 -> (index - 1).toLong()
                sameLane -> slice.toLong() * segmentLength + index - 1
                else -> slice.toLong() * segmentLength + if (index == 0) -1 else 0
            }
        } else {
            if (sameLane) {
                laneLength.toLong() - segmentLength + index - 1
            } else {
                laneLength.toLong() - segmentLength + if (index == 0) -1 else 0
            }
        }

        var relativePosition = pseudoRand.toULong()
        relativePosition = (relativePosition * relativePosition) shr 32
        relativePosition = referenceAreaSize.toULong() - 1uL -
            ((referenceAreaSize.toULong() * relativePosition) shr 32)

        val startPosition: Long = when {
            pass == 0 -> 0L
            slice == SYNC_POINTS - 1 -> 0L
            else -> (slice + 1).toLong() * segmentLength
        }

        return ((startPosition.toULong() + relativePosition) % laneLength.toULong()).toInt()
    }

    /**
     * The compression function G (section 3.5): `next = R xor P_columns(P_rows(R))` where
     * `R = prev xor ref`, optionally XORing the previous contents of `next` in.
     *
     * [ref] and [next] may be the same array at the same offset: the working copies are
     * taken before anything is written back.
     */
    private fun fillBlock(
        prev: LongArray,
        prevOff: Int,
        ref: LongArray,
        refOff: Int,
        next: LongArray,
        nextOff: Int,
        withXor: Boolean,
        blockR: LongArray,
        blockTmp: LongArray,
    ) {
        if (withXor) {
            for (i in 0 until WORDS_PER_BLOCK) {
                val r = ref[refOff + i] xor prev[prevOff + i]
                blockR[i] = r
                blockTmp[i] = r xor next[nextOff + i]
            }
        } else {
            for (i in 0 until WORDS_PER_BLOCK) {
                val r = ref[refOff + i] xor prev[prevOff + i]
                blockR[i] = r
                blockTmp[i] = r
            }
        }

        // Rows: the eight 16-word runs.
        for (i in 0 until 8) {
            val b = 16 * i
            round(
                blockR,
                b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15,
            )
        }
        // Columns: the word pairs 2i, 2i+1 taken from each of the eight rows.
        for (i in 0 until 8) {
            val b = 2 * i
            round(
                blockR,
                b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113,
            )
        }

        for (i in 0 until WORDS_PER_BLOCK) {
            next[nextOff + i] = blockTmp[i] xor blockR[i]
        }
    }

    /** The BLAKE2b round function P over the sixteen words at the given indices. */
    @Suppress("LongParameterList")
    private fun round(
        b: LongArray,
        i0: Int, i1: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int, i12: Int, i13: Int, i14: Int, i15: Int,
    ) {
        var v0 = b[i0]; var v1 = b[i1]; var v2 = b[i2]; var v3 = b[i3]
        var v4 = b[i4]; var v5 = b[i5]; var v6 = b[i6]; var v7 = b[i7]
        var v8 = b[i8]; var v9 = b[i9]; var v10 = b[i10]; var v11 = b[i11]
        var v12 = b[i12]; var v13 = b[i13]; var v14 = b[i14]; var v15 = b[i15]

        // G(v0, v4, v8, v12)
        v0 = blaMka(v0, v4); v12 = (v12 xor v0).rotateRight(32)
        v8 = blaMka(v8, v12); v4 = (v4 xor v8).rotateRight(24)
        v0 = blaMka(v0, v4); v12 = (v12 xor v0).rotateRight(16)
        v8 = blaMka(v8, v12); v4 = (v4 xor v8).rotateRight(63)

        // G(v1, v5, v9, v13)
        v1 = blaMka(v1, v5); v13 = (v13 xor v1).rotateRight(32)
        v9 = blaMka(v9, v13); v5 = (v5 xor v9).rotateRight(24)
        v1 = blaMka(v1, v5); v13 = (v13 xor v1).rotateRight(16)
        v9 = blaMka(v9, v13); v5 = (v5 xor v9).rotateRight(63)

        // G(v2, v6, v10, v14)
        v2 = blaMka(v2, v6); v14 = (v14 xor v2).rotateRight(32)
        v10 = blaMka(v10, v14); v6 = (v6 xor v10).rotateRight(24)
        v2 = blaMka(v2, v6); v14 = (v14 xor v2).rotateRight(16)
        v10 = blaMka(v10, v14); v6 = (v6 xor v10).rotateRight(63)

        // G(v3, v7, v11, v15)
        v3 = blaMka(v3, v7); v15 = (v15 xor v3).rotateRight(32)
        v11 = blaMka(v11, v15); v7 = (v7 xor v11).rotateRight(24)
        v3 = blaMka(v3, v7); v15 = (v15 xor v3).rotateRight(16)
        v11 = blaMka(v11, v15); v7 = (v7 xor v11).rotateRight(63)

        // G(v0, v5, v10, v15)
        v0 = blaMka(v0, v5); v15 = (v15 xor v0).rotateRight(32)
        v10 = blaMka(v10, v15); v5 = (v5 xor v10).rotateRight(24)
        v0 = blaMka(v0, v5); v15 = (v15 xor v0).rotateRight(16)
        v10 = blaMka(v10, v15); v5 = (v5 xor v10).rotateRight(63)

        // G(v1, v6, v11, v12)
        v1 = blaMka(v1, v6); v12 = (v12 xor v1).rotateRight(32)
        v11 = blaMka(v11, v12); v6 = (v6 xor v11).rotateRight(24)
        v1 = blaMka(v1, v6); v12 = (v12 xor v1).rotateRight(16)
        v11 = blaMka(v11, v12); v6 = (v6 xor v11).rotateRight(63)

        // G(v2, v7, v8, v13)
        v2 = blaMka(v2, v7); v13 = (v13 xor v2).rotateRight(32)
        v8 = blaMka(v8, v13); v7 = (v7 xor v8).rotateRight(24)
        v2 = blaMka(v2, v7); v13 = (v13 xor v2).rotateRight(16)
        v8 = blaMka(v8, v13); v7 = (v7 xor v8).rotateRight(63)

        // G(v3, v4, v9, v14)
        v3 = blaMka(v3, v4); v14 = (v14 xor v3).rotateRight(32)
        v9 = blaMka(v9, v14); v4 = (v4 xor v9).rotateRight(24)
        v3 = blaMka(v3, v4); v14 = (v14 xor v3).rotateRight(16)
        v9 = blaMka(v9, v14); v4 = (v4 xor v9).rotateRight(63)

        b[i0] = v0; b[i1] = v1; b[i2] = v2; b[i3] = v3
        b[i4] = v4; b[i5] = v5; b[i6] = v6; b[i7] = v7
        b[i8] = v8; b[i9] = v9; b[i10] = v10; b[i11] = v11
        b[i12] = v12; b[i13] = v13; b[i14] = v14; b[i15] = v15
    }

    /**
     * `x + y + 2 * lo32(x) * lo32(y)`, the multiply-add that makes Argon2's round
     * function expensive to shortcut. Everything is mod 2^64, so the Long overflow in
     * the product is deliberate.
     */
    private fun blaMka(x: Long, y: Long): Long =
        x + y + 2L * ((x and 0xFFFFFFFFL) * (y and 0xFFFFFFFFL))

    // -- Little-endian helpers ---------------------------------------------

    private fun le32(value: Int): ByteArray {
        val out = ByteArray(4)
        writeLe32(out, 0, value)
        return out
    }

    private fun writeLe32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun loadBlock(dst: LongArray, dstOff: Int, src: ByteArray) {
        for (i in 0 until WORDS_PER_BLOCK) {
            val base = i * 8
            var value = 0L
            for (j in 7 downTo 0) {
                value = (value shl 8) or (src[base + j].toLong() and 0xFF)
            }
            dst[dstOff + i] = value
        }
    }

    private fun storeBlock(dst: ByteArray, src: LongArray, srcOff: Int) {
        for (i in 0 until WORDS_PER_BLOCK) {
            var value = src[srcOff + i]
            val base = i * 8
            for (j in 0 until 8) {
                dst[base + j] = (value and 0xFF).toByte()
                value = value ushr 8
            }
        }
    }

    private const val VERSION = 0x13
    private const val TYPE_ARGON2ID = 2
    private const val SYNC_POINTS = 4
    private const val WORDS_PER_BLOCK = 128
    private const val BLOCK_BYTES = 1024
    private const val ADDRESSES_IN_BLOCK = 128
    private const val H0_LENGTH = 64
    private const val MIN_SALT_LENGTH = 8
    private const val MIN_TAG_LENGTH = 4
    private const val MAX_PARALLELISM = 255

    /** 4 GiB. The block array is indexed by [Int], which caps usable memory well below this. */
    private const val MAX_MEMORY_KIB = 4 * 1024 * 1024
}
