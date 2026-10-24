package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.crypto.KeyMaterial

sealed interface VaultAccessResult {
    class Success(val material: KeyMaterial) : VaultAccessResult {
        override fun toString() = "VaultAccessResult.Success([redacted])"
    }
    data object Cancelled : VaultAccessResult
    data class Unavailable(val reason: String) : VaultAccessResult
    data object NoVault : VaultAccessResult
    data object CleanupRequired : VaultAccessResult
}

sealed interface VaultMaintenanceResult {
    data class Completed(val completedWalletIds: Set<String>, val unrecoveredWalletIds: Set<String>) : VaultMaintenanceResult
    data object Cancelled : VaultMaintenanceResult
    data class Unavailable(val reason: String) : VaultMaintenanceResult
}
