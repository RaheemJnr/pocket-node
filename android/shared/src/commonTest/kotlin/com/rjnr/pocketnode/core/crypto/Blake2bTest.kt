package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Known answers for BLAKE2b-256 under CKB's `"ckb-default-hash"` personalization (#454). */
class Blake2bTest {

    @Test
    fun personalizationIsSixteenBytes() {
        assertEquals(16, Blake2b.CKB_HASH_PERSONALIZATION.size)
    }

    @Test
    fun hashesEmptyInputToTheCkbEmptyHash() {
        assertEquals(
            "0x44f4c69744d5f8c55d642062949dcae49bc4e7ef43d388c5a12f42b5633d163e",
            Blake2b.digest(ByteArray(0)).toHexString(),
        )
    }

    @Test
    fun incrementalMatchesOneShot() {
        val part1 = ByteArray(37) { it.toByte() }
        val part2 = ByteArray(91) { (it * 7).toByte() }
        val incremental = Blake2b().update(part1).update(part2).doFinal()
        assertContentEquals(Blake2b.digest(part1 + part2), incremental)
    }

    @Test
    fun doFinalResetsSoTheInstanceIsReusable() {
        val hasher = Blake2b()
        hasher.update(byteArrayOf(1, 2, 3))
        hasher.doFinal()
        assertContentEquals(Blake2b.digest(ByteArray(0)), hasher.doFinal())
    }
}
