package com.rjnr.pocketnode.ui.screens.recovery

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecoveryStage { RESTORE_FAILED, RESTORING, SUCCESS }
data class MissingWallet(
    val walletId: String, val name: String, val isRawKey: Boolean,
    val hasBackup: Boolean = false, val cleanupPending: Boolean = false, val hasLegacyArchive: Boolean = false,
)
data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.RESTORE_FAILED,
    val missingWallets: List<MissingWallet> = emptyList(),
    val outcomes: Map<String, String> = emptyMap(),
    val restoredWalletCount: Int = 0,
    val error: String? = null,
) {
    val missingWalletNames get() = missingWallets.map { it.name }
}

/** Retains only public wallet metadata and operation outcomes; never input or decrypted material. */
@HiltViewModel
class RecoveryViewModel @Inject constructor(private val operations: RecoveryOperations) : ViewModel() {
    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState
    private val restored = mutableSetOf<String>()
    private var operation: Job? = null
    init { viewModelScope.launch { refresh() } }

    private suspend fun refresh() {
        val wallets = operations.wallets()
        if (wallets.isNotEmpty()) operations.markIncomplete()
        _uiState.update { it.copy(missingWallets = wallets, restoredWalletCount = restored.size) }
    }
    fun requestMissingRestore() { if (operation?.isActive != true) viewModelScope.launch { refresh() } }
    fun restoreBackup(activity: FragmentActivity, id: String) = run(id) { operations.backup(activity, id) }
    fun resumeCleanup(activity: FragmentActivity, id: String) = run(id) { operations.cleanup(activity, id) }
    fun unlock(activity: FragmentActivity, id: String, preference: VaultReadPreference) = run(id) { operations.unlock(activity, id, preference) }
    fun recoverExternal(activity: FragmentActivity, id: String, privateKey: Boolean) {
        run(id) {
            when (val result = operations.external(activity, id, privateKey)) {
                RecoveredKeyRestorer.RekeyResult.InvalidInput -> RecoveredKeyRestorer.Outcome.Failed("Invalid recovery secret. Check the words or 64-character private key.")
                RecoveredKeyRestorer.RekeyResult.NotThisWallet -> RecoveredKeyRestorer.Outcome.AddressMismatch
                RecoveredKeyRestorer.RekeyResult.NotMissing -> RecoveredKeyRestorer.Outcome.Failed("Wallet state changed. Refresh and retry authentication.")
                is RecoveredKeyRestorer.RekeyResult.Done -> {
                    result.outcomes.forEach { (wallet, outcome) -> record(wallet, outcome) }
                    result.outcomes[id] ?: RecoveredKeyRestorer.Outcome.Cancelled
                }
            }
        }
    }
    fun cancelOperation() { operation?.cancel() }
    private fun run(id: String, action: suspend () -> RecoveredKeyRestorer.Outcome) {
        if (operation?.isActive == true || _uiState.value.missingWallets.none { it.walletId == id }) return
        _uiState.update { it.copy(stage = RecoveryStage.RESTORING, error = null) }
        operation = viewModelScope.launch {
            try {
                record(id, action())
                refresh()
                val complete = operations.complete()
                _uiState.update { it.copy(stage = if (complete) RecoveryStage.SUCCESS else RecoveryStage.RESTORE_FAILED) }
            } catch (e: CancellationException) {
                record(id, RecoveredKeyRestorer.Outcome.Cancelled)
                _uiState.update { it.copy(stage = RecoveryStage.RESTORE_FAILED) }
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(stage = RecoveryStage.RESTORE_FAILED, error = "Recovery did not complete. Saved wallet data is preserved; retry authentication.") }
            }
        }
    }
    private fun record(id: String, outcome: RecoveredKeyRestorer.Outcome) {
        if (outcome == RecoveredKeyRestorer.Outcome.Restored) restored += id
        val message = when (outcome) {
            RecoveredKeyRestorer.Outcome.Restored -> "Access restored and verified."
            RecoveredKeyRestorer.Outcome.Cancelled -> "Cancelled. Saved recovery data is preserved."
            RecoveredKeyRestorer.Outcome.CleanupRequired -> "Verified protection is saved. Authenticate retained legacy sources to finish cleanup."
            RecoveredKeyRestorer.Outcome.AddressMismatch -> "This recovery secret does not match this wallet. Nothing was replaced."
            RecoveredKeyRestorer.Outcome.NoBackup -> "No usable backup. Keep this wallet read-only or use your external recovery secret."
            RecoveredKeyRestorer.Outcome.NoWalletRow -> "Wallet no longer exists."
            RecoveredKeyRestorer.Outcome.NeedsNoLockConsent -> "Wallet passphrase protection is required."
            is RecoveredKeyRestorer.Outcome.Failed -> outcome.reason
        }
        _uiState.update { state -> state.copy(outcomes = state.outcomes + (id to message),
            missingWallets = if (outcome == RecoveredKeyRestorer.Outcome.CleanupRequired)
                state.missingWallets.map { if (it.walletId == id) it.copy(cleanupPending = true) else it }
            else state.missingWallets) }
    }
}
