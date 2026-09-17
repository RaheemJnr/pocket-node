package com.rjnr.pocketnode.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrUriParserTest {

    @Test
    fun `plain ckb mainnet address passes through unchanged`() {
        val addr = "ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r"
        assertEquals(addr, extractCkbAddress(addr))
    }

    @Test
    fun `plain ckt testnet address passes through unchanged`() {
        val addr = "ckt1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r"
        assertEquals(addr, extractCkbAddress(addr))
    }

    @Test
    fun `joyid https URL extracts embedded ckb address`() {
        val url = "https://app.joy.id/account/ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r"
        assertEquals("ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r", extractCkbAddress(url))
    }

    @Test
    fun `joyid URI scheme extracts embedded address`() {
        val uri = "joyid://ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r"
        assertEquals("ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r", extractCkbAddress(uri))
    }

    @Test
    fun `https URL with address query param extracts address`() {
        val url = "https://joyid.app/send?to=ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r"
        assertEquals("ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r", extractCkbAddress(url))
    }

    @Test
    fun `uppercase plain address is extracted lowercased`() {
        val addr = "CKB1QZDA0CR08M85HC8JYU3Z3FUHGXK23GHRB64KZK3R"
        assertEquals("ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r", extractCkbAddress(addr))
    }

    @Test
    fun `uppercase address inside a ckb URI is extracted lowercased`() {
        val uri = "ckb:CKT1QZDA0CR08M85HC8JYU3Z3FUHGXK23GHRB64KZK3R"
        assertEquals("ckt1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r", extractCkbAddress(uri))
    }

    @Test
    fun `mixed case address is rejected`() {
        assertNull(extractCkbAddress("ckt1QzDa0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r"))
    }

    @Test
    fun `unrecognized format returns null`() {
        assertNull(extractCkbAddress("not-an-address"))
        assertNull(extractCkbAddress("https://example.com/no-ckb"))
    }

    @Test
    fun `null input returns null`() {
        assertNull(extractCkbAddress(null))
    }
}
