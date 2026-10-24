package com.rjnr.pocketnode.data.vault

import org.junit.Assert.*
import org.junit.Test

class WalletPassphrasePolicyTest {
    // Catch off-by-one length, silent trim/normalization and UTF-16-unit counting.
    @Test fun policyCountsUnicodeCodePointsAndPreservesSpaces() {
        assertFalse(WalletPassphrasePolicy.validate("123456789012345".toCharArray()))
        assertTrue(WalletPassphrasePolicy.validate("1234567890123456".toCharArray()))
        assertTrue(WalletPassphrasePolicy.validate(" 12345678901234 ".toCharArray()))
        assertFalse(WalletPassphrasePolicy.validate("🔐".repeat(15).toCharArray()))
        assertTrue(WalletPassphrasePolicy.validate("🔐".repeat(16).toCharArray()))
        assertFalse(WalletPassphrasePolicy.validate(("a".repeat(16) + '\uD800').toCharArray()))
        assertFalse(WalletPassphrasePolicy.validate(CharArray(1025) { 'a' }))
    }
}
