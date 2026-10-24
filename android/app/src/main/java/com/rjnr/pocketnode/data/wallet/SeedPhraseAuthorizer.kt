package com.rjnr.pocketnode.data.wallet

import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

/** Authenticates through the central reader for every storage version and requires an actual seed. */
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
            is WalletKeyReader.MaterialResult.Success -> try {
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
            finally { material.privateKey.fill(0) }
            is WalletKeyReader.MaterialResult.Cancelled -> SeedResult.Cancelled
            is WalletKeyReader.MaterialResult.KeyInvalidated -> SeedResult.KeyInvalidated
            is WalletKeyReader.MaterialResult.AuthError -> SeedResult.Failed(material.message.toString())
            is WalletKeyReader.MaterialResult.NotAvailable -> SeedResult.Failed(material.reason)
        }
    }
}
