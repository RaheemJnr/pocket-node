package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.crypto.KeyMaterial

/** Witness is freshly verified for this generation and operation-scoped; implementations must never retain it. */
fun interface VaultLegacyCleanup {
    suspend fun clean(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial): Boolean
}
