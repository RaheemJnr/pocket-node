package com.rjnr.pocketnode.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.vault.*
import javax.inject.Inject

data class WalletSecurityStatus(val walletId: String, val name: String, val message: String)
data class VaultSettingsResult(val message: String, val wallets: List<WalletSecurityStatus> = emptyList())

/** Converts secret-bearing coordinator results to public outcomes before returning to the ViewModel. */
class SecurityVaultOperations @Inject constructor(
    private val coordinator: WalletVaultCoordinator,
    private val store: WalletVaultStore,
    private val wallets: WalletDao,
) {
    suspend fun walletNames(): List<WalletSecurityStatus> {
        val status = try { store.snapshot() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val unrecovered = status?.unrecoveredWalletIds.orEmpty()
        return wallets.getAll().map { WalletSecurityStatus(it.walletId, it.name,
            if (status == null) "Protection status unavailable — choose authentication or recovery"
            else if (it.walletId in unrecovered) "Previous passphrase or external recovery required" else "") }
    }
    suspend fun change(activity: FragmentActivity): VaultSettingsResult = summarize(coordinator.changePassphrase(activity))
    suspend fun reset(activity: FragmentActivity, mode: VaultDevicePromptMode?): VaultSettingsResult = summarize(coordinator.resetPassphrase(activity, mode))
    suspend fun enroll(activity: FragmentActivity, id: String, mode: VaultDevicePromptMode?): VaultSettingsResult {
        val message = when (coordinator.enrollDevice(activity, id, mode)) {
            is VaultAccessResult.Success -> "Device authentication enrolled. Wallet passphrase protection remains available."
            VaultAccessResult.Cancelled -> "Cancelled. Previous protection is preserved."
            VaultAccessResult.CleanupRequired -> "Legacy cleanup must finish in wallet recovery before enrollment."
            else -> "Device enrollment did not complete. Use the wallet passphrase or recover the previous profile first."
        }
        return VaultSettingsResult(message)
    }
    private suspend fun summarize(result: VaultMaintenanceResult): VaultSettingsResult = when (result) {
        VaultMaintenanceResult.Cancelled -> VaultSettingsResult("Cancelled. Previous passphrase protection is preserved.")
        is VaultMaintenanceResult.Unavailable -> VaultSettingsResult("Passphrase update did not complete. Verify your current passphrase. Unrecovered wallets from a previous reset block a known-passphrase change; recover those wallets first.")
        is VaultMaintenanceResult.Completed -> VaultSettingsResult(
            "Updated ${result.completedWalletIds.size} wallet(s); ${result.unrecoveredWalletIds.size} still need their previous passphrase or external recovery secret.",
            wallets.getAll().map { WalletSecurityStatus(it.walletId, it.name, when (it.walletId) {
                in result.completedWalletIds -> "Passphrase updated"
                in result.unrecoveredWalletIds -> "Not reset — previous protection preserved; recovery required"
                else -> "Not changed"
            }) })
    }
}
