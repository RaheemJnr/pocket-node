package com.rjnr.pocketnode.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Debug-build log scrubbing (#321): addresses and tx hashes in logcat are
 * readable via adb/bugreports on tester devices. Helpers keep enough prefix
 * to correlate log lines without exposing the full linkable value.
 */
class RedactionTest {

    @Test
    fun `redactAddress keeps prefix and suffix only`() {
        val addr = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq2qf8keemy2p5uu0g0gn8cd4ju23s5269qk8rg4r"
        assertEquals("ckt1qzda…8rg4r", addr.redactAddress())
    }

    @Test
    fun `redactAddress leaves short strings unchanged`() {
        assertEquals("0xab12", "0xab12".redactAddress())
    }

    @Test
    fun `redactHash keeps prefix only`() {
        val hash = "0x" + "ab".repeat(32)
        assertEquals("0xabababab…", hash.redactHash())
    }

    @Test
    fun `redactHash leaves short strings unchanged`() {
        assertEquals("0xab", "0xab".redactHash())
    }
}
