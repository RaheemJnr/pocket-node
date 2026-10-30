package com.rjnr.pocketnode.util

import org.junit.Assert.*
import org.junit.Test

class AmountUtilsTest {

    @Test
    fun `sanitizeAmount passes through valid 8-decimal input`() {
        assertEquals("100.12345678", sanitizeAmount("100.12345678"))
    }

    @Test
    fun `sanitizeAmount rejects excess precision without truncating`() {
        assertNull(sanitizeAmount("100.123456789"))
        assertNull(sanitizeAmount("0.000000019999"))
    }

    @Test
    fun `sanitizeAmount passes whole numbers`() {
        assertEquals("100", sanitizeAmount("100"))
        assertEquals("61", sanitizeAmount("61"))
    }

    @Test
    fun `sanitizeAmount passes partial decimal input`() {
        assertEquals("100.", sanitizeAmount("100."))
        assertEquals("100.1", sanitizeAmount("100.1"))
    }

    @Test
    fun `sanitizeAmount rejects non-numeric input`() {
        assertNull(sanitizeAmount("abc"))
        assertNull(sanitizeAmount("100.1.2"))
    }

    @Test
    fun `entry and conversion share ASCII grammar and bounds`() {
        listOf("٦١", "６１", "6١", "-61", "+61", "6e1", "1,000", "61,5", " 61", "61 ", "61\n",
            "92233720368.54775808", "1.000000000").forEach {
            assertNull(it, sanitizeAmount(it))
            assertNull(it, com.rjnr.pocketnode.core.format.ckbToShannons(it))
        }
        listOf("0", ".5", "61.", "61.00000001", "92233720368.54775807").forEach {
            assertEquals(it, sanitizeAmount(it))
            assertNotNull(com.rjnr.pocketnode.core.format.ckbToShannons(it))
        }
        assertEquals(".", sanitizeAmount("."))
        assertNull(com.rjnr.pocketnode.core.format.ckbToShannons("."))
    }

    @Test
    fun `sanitizeAmount accepts empty string`() {
        assertEquals("", sanitizeAmount(""))
    }
}
