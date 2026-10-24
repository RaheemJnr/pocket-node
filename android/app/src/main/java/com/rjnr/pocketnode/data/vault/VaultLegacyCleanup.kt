package com.rjnr.pocketnode.data.vault

fun interface VaultLegacyCleanup {
    suspend fun clean(walletId: String): Boolean
}
