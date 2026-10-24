package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.crypto.KeyMaterial

sealed interface VaultAccessResult {
    class Success(val material: KeyMaterial) : VaultAccessResult {
        override fun toString() = "VaultAccessResult.Success([redacted])"
    }
    data object Cancelled : VaultAccessResult
    data class Unavailable(val reason: String, val kind: VaultFailureKind = VaultFailureKind.UNKNOWN) : VaultAccessResult
    data object NoVault : VaultAccessResult
    data object CleanupRequired : VaultAccessResult
}

sealed interface VaultMaintenanceResult {
    data class Completed(val completedWalletIds: Set<String>, val unrecoveredWalletIds: Set<String>) : VaultMaintenanceResult
    data object Cancelled : VaultMaintenanceResult
    data class Unavailable(val reason: String) : VaultMaintenanceResult
}

/** Explicit UI choice; cancellation never changes the selected route. */
enum class VaultReadPreference { DEFAULT, PASSPHRASE, BIOMETRIC_ONLY, CREDENTIAL_ONLY }

/** No category proves a lost seed. Recovery must still offer independent credentials and preserve sources. */
enum class VaultFailureKind {
    UNKNOWN, MISSING_DEVICE_KEY, INVALIDATED_DEVICE_KEY, NO_DEVICE_WRAP, NO_DEVICE_LOCK,
    UNSUPPORTED_DEVICE_AUTH, TEMPORARY_DEVICE_FAILURE, CREDENTIAL_OR_WRAP_INTEGRITY,
    PAYLOAD_INTEGRITY, MALFORMED_OR_UNSUPPORTED, VERIFICATION_FAILED,
}
