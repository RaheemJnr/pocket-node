package com.rjnr.pocketnode.data.wallet

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Candidate derivation for HD sub-account discovery (#82 / #371).
 *
 * Sub-account N derives at m/44'/309'/N'/0/0 (the account index is the only
 * variable — see [MnemonicManager.derivePrivateKey] and
 * [WalletRepository.createSubAccount]). Restoring a parent seed therefore
 * makes its sub-accounts *derivable*, but the embedded light client can only
 * report history for scripts it has been told to sync. Discovery is a
 * two-step dance:
 *
 *  1. At import, while the mnemonic is in memory, compute the lock-script
 *     args for candidate indices 1..[CANDIDATE_WINDOW] (this class) and
 *     persist them — args only, never key material.
 *  2. Register those scripts for sync; once the filter sync has run, any
 *     candidate with on-chain activity is offered to the user as a one-tap
 *     restore (phase 2).
 *
 * Sub-account indices are assigned contiguously from 1 (max+1 on create), so
 * a modest window covers real usage; the standard BIP44 gap-limit scan is
 * unnecessary here and each extra registered script costs light-client sync
 * time.
 */
@Singleton
class SubAccountDiscovery @Inject constructor(
    private val mnemonicManager: MnemonicManager,
    private val keyManager: KeyManager,
) {

    /** A derivable sub-account slot: its BIP44 account index + lock args. */
    data class Candidate(val accountIndex: Int, val scriptArgs: String)

    /**
     * Derive the candidate lock-script args for [window] sub-account indices
     * (1-based; index 0 is the parent itself). Pure computation — no I/O, no
     * key material retained beyond the call.
     */
    fun deriveCandidates(
        words: List<String>,
        passphrase: String = "",
        window: Int = CANDIDATE_WINDOW,
    ): List<Candidate> {
        require(window > 0) { "window must be positive" }
        val seed = mnemonicManager.mnemonicToSeed(words, passphrase)
        return (1..window).map { index ->
            val privateKey = mnemonicManager.derivePrivateKey(seed, accountIndex = index)
            val publicKey = keyManager.derivePublicKey(privateKey)
            privateKey.fill(0) // wipe: args are public, the key must not linger
            Candidate(
                accountIndex = index,
                scriptArgs = keyManager.deriveLockScript(publicKey).args,
            )
        }
    }

    companion object {
        /**
         * Indices scanned per parent. Sub-accounts are created contiguously
         * from 1, so 10 covers any realistic wallet; phase 2 can extend the
         * window when the highest index in it turns out active.
         */
        const val CANDIDATE_WINDOW = 10
    }
}
