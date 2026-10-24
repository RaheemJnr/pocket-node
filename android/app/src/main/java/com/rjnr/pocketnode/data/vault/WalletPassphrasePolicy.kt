package com.rjnr.pocketnode.data.vault

/** Counts Unicode code points, with no trimming or normalization. Callers own their buffers. */
object WalletPassphrasePolicy {
    const val MIN_CODE_POINTS = 16
    const val MAX_UTF16_UNITS = 1024

    fun validate(passphrase: CharArray): Boolean {
        if (passphrase.size > MAX_UTF16_UNITS || !wellFormed(passphrase)) return false
        return Character.codePointCount(passphrase, 0, passphrase.size) >= MIN_CODE_POINTS
    }

    internal fun wellFormed(chars: CharArray): Boolean {
        var index = 0
        while (index < chars.size) {
            val char = chars[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == chars.size || !Character.isLowSurrogate(chars[index++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }
}
