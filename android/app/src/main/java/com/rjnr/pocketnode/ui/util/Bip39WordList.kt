package com.rjnr.pocketnode.ui.util

import com.rjnr.pocketnode.core.crypto.Bip39

/**
 * Word-entry helpers for the mnemonic import UI.
 *
 * The list itself now comes from [Bip39], which needs the same 2048 words for
 * the checksum. The app used to carry a second, hand-maintained copy in
 * `Bip39Words.kt`; two copies of a list whose ORDER is a checksum is a
 * divergence waiting to happen, so there is one (#507).
 */
object Bip39WordList {
    val WORDS: List<String> = Bip39.WORDLIST

    private val wordSet: Set<String> = WORDS.toHashSet()

    fun getSuggestions(prefix: String, limit: Int = 4): List<String> {
        if (prefix.length < 2) return emptyList()
        val lower = prefix.lowercase()
        return WORDS.filter { it.startsWith(lower) }.take(limit)
    }

    fun isValidWord(word: String): Boolean = wordSet.contains(word.lowercase())
}
