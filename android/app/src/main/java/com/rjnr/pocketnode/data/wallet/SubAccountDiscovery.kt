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

    /**
     * A derivable-but-not-owned slot: full BIP44 path + lock args. Account-
     * axis candidates (m/44'/309'/N'/0/0) keep their account index for the
     * restore flow; chain-axis candidates (#382 Tier 2) live on account 0.
     */
    data class Candidate(
        val accountIndex: Int,
        val scriptArgs: String,
        val derivationPath: String,
    )

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
            Candidate(
                accountIndex = index,
                scriptArgs = argsFor(seed, accountIndex = index, chainIndex = 0, addressIndex = 0),
                derivationPath = accountPath(index),
            )
        }
    }

    /**
     * #382 Tier 2: derive gap-limit candidates along account 0's receiving
     * and change chains — m/44'/309'/0'/{0|1}/{0..window} — the paths Neuron
     * and standard BIP44 wallets actually spread funds across. Chain 0 /
     * index 0 is the parent itself and is skipped. Args only, keys wiped.
     */
    fun deriveChainCandidates(
        words: List<String>,
        passphrase: String = "",
        window: Int = CHAIN_GAP_WINDOW,
    ): List<Candidate> {
        require(window > 0) { "window must be positive" }
        val seed = mnemonicManager.mnemonicToSeed(words, passphrase)
        return buildList {
            for (chain in 0..1) {
                for (index in 0..window) {
                    if (chain == 0 && index == 0) continue // the parent's own slot
                    add(
                        Candidate(
                            accountIndex = 0,
                            scriptArgs = argsFor(seed, 0, chain, index),
                            derivationPath = chainPath(chain, index),
                        )
                    )
                }
            }
        }
    }

    private fun argsFor(seed: ByteArray, accountIndex: Int, chainIndex: Int, addressIndex: Int): String {
        val privateKey = mnemonicManager.derivePrivateKey(
            seed, accountIndex = accountIndex, chainIndex = chainIndex, addressIndex = addressIndex
        )
        val publicKey = keyManager.derivePublicKey(privateKey)
        privateKey.fill(0) // wipe: args are public, the key must not linger
        return keyManager.deriveLockScript(publicKey).args
    }

    companion object {
        /**
         * Indices scanned per parent. Sub-accounts are created contiguously
         * from 1, so 10 covers any realistic wallet; phase 2 can extend the
         * window when the highest index in it turns out active.
         */
        const val CANDIDATE_WINDOW = 10

        /**
         * Address indices scanned per chain for the #382 gap-limit scan
         * (0..window inclusive per chain). BIP44's standard gap limit is 20
         * consecutive unused; a fixed 0..20 window covers real Neuron usage,
         * and the reconciler can extend when index 20 turns out active.
         */
        const val CHAIN_GAP_WINDOW = 20

        fun accountPath(accountIndex: Int) = "m/44'/309'/$accountIndex'/0/0"
        fun chainPath(chainIndex: Int, addressIndex: Int) = "m/44'/309'/0'/$chainIndex/$addressIndex"
    }
}
