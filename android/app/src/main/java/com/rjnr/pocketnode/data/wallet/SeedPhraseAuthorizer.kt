package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Acquires a wallet's recovery-phrase words for an operation that derives keys
 * from the seed (the #382 gap-limit scan and sweep), driving the V2 auth-bound
 * BiometricPrompt when needed.
 *
 * ## Why this exists (#408)
 *
 * `GatewayRepository.runGapLimitScan()` / `sweepGapLimitFunds()` originally read
 * the seed via `getMnemonic()` with no cipher. For a kdfVersion=2 (auth-bound)
 * wallet that throws "caller must supply an authenticated Cipher", so the scan
 * and sweep failed on every V2 wallet. Sends already solved this by routing V2
 * through [WalletKeyReader]; the scan/sweep were never migrated. This is the
 * one boundary that turns a V2 wallet id + Activity into the seed words, so
 * the two ViewModels share it instead of each re-deriving the biometric dance.
 *
 * V1 wallets keep using the repository's existing `getMnemonic()` path (which
 * has an EncryptedSharedPreferences fallback this reader does not); callers
 * dispatch on `kdfVersion` and only reach here for V2.
 */
@Singleton
class SeedPhraseAuthorizer @Inject constructor(
    private val walletKeyReader: WalletKeyReader,
) {

    sealed interface SeedResult {
        /** Seed unlocked; [words] is the mnemonic split on whitespace. */
        data class Words(val words: List<String>) : SeedResult
        /** User dismissed the biometric prompt. Caller should quietly reset UI. */
        object Cancelled : SeedResult
        /**
         * V2 Keystore key wiped by a biometric-enrollment change. The encrypted
         * seed on disk is unrecoverable; caller must surface a re-import flow.
         */
        object KeyInvalidated : SeedResult
        /** Auth error, no seed on the row, or a raw-key wallet with no mnemonic. */
        data class Failed(val reason: String) : SeedResult
    }

    /**
     * Drive the (V2) authenticated read for [walletId] and return the seed
     * words. Exactly one BiometricPrompt for a V2 wallet; silent for V1 (though
     * callers normally reserve this for V2 and take the repository path for V1).
     */
    suspend fun authorize(
        activity: FragmentActivity,
        walletId: String,
        promptTitle: String,
        promptSubtitle: String,
    ): SeedResult = toSeedResult(
        walletKeyReader.readKeyMaterial(activity, walletId, promptTitle, promptSubtitle)
    )

    companion object {
        /** Pure mapping from a key-material read to a seed outcome. */
        fun toSeedResult(material: WalletKeyReader.MaterialResult): SeedResult = when (material) {
            is WalletKeyReader.MaterialResult.Success -> {
                val words = material.mnemonic
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(Regex("\\s+"))
                if (words.isNullOrEmpty()) {
                    SeedResult.Failed("Recovery phrase unavailable for this wallet")
                } else {
                    SeedResult.Words(words)
                }
            }
            is WalletKeyReader.MaterialResult.Cancelled -> SeedResult.Cancelled
            is WalletKeyReader.MaterialResult.KeyInvalidated -> SeedResult.KeyInvalidated
            is WalletKeyReader.MaterialResult.AuthError -> SeedResult.Failed(material.message.toString())
            is WalletKeyReader.MaterialResult.NotAvailable -> SeedResult.Failed(material.reason)
        }
    }
}
