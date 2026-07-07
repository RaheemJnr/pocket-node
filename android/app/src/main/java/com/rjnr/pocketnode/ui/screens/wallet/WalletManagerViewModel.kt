package com.rjnr.pocketnode.ui.screens.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.components.WalletGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WalletManagerVM"

@HiltViewModel
class WalletManagerViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository,
    private val subAccountCandidateDao: com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao,
    private val walletKeyReader: com.rjnr.pocketnode.data.wallet.WalletKeyReader,
    private val walletKeyWriter: com.rjnr.pocketnode.data.wallet.WalletKeyWriter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletManagerUiState())
    val uiState: StateFlow<WalletManagerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            walletRepository.walletsFlow.collect { wallets ->
                val parents = wallets.filter { it.parentWalletId == null }
                val groups = parents.map { parent ->
                    WalletGroup(
                        wallet = parent,
                        subAccounts = wallets.filter { it.parentWalletId == parent.walletId }
                    )
                }
                _uiState.update { it.copy(walletGroups = groups) }
            }
        }
        // Discovery restore banner (#82 phase 2): sub-account slots whose
        // scripts showed on-chain history after a parent seed import.
        // Account-axis only — chain-axis gap-limit slots (#382, accountIndex
        // 0) are never restorable as wallets; they surface via the Tier 2
        // found-funds flow instead.
        viewModelScope.launch {
            subAccountCandidateDao
                .observeByState(
                    com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity.STATE_FOUND
                )
                .collect { found ->
                    _uiState.update { st -> st.copy(foundCandidates = found.filter { it.accountIndex >= 1 }) }
                }
        }
    }

    /**
     * One-tap restore of discovered sub-accounts (#82 / #371). Per parent:
     * one auth prompt to read the parent mnemonic, then one persist prompt
     * per restored account — the same two-prompt contract as manual
     * sub-account creation, just batched and user-initiated from the banner.
     */
    fun restoreFoundSubAccounts(activity: androidx.fragment.app.FragmentActivity) {
        val found = _uiState.value.foundCandidates
        if (found.isEmpty() || _uiState.value.isRestoring) return
        _uiState.update { it.copy(isRestoring = true) }
        viewModelScope.launch {
            try {
                var restored = 0
                found.groupBy { it.parentWalletId }.forEach { (parentId, candidates) ->
                    val readResult = walletKeyReader.readKeyMaterial(
                        activity = activity,
                        walletId = parentId,
                        promptTitle = "Authenticate to restore",
                        promptSubtitle = "Verify your identity to restore discovered sub-accounts.",
                    )
                    if (readResult !is com.rjnr.pocketnode.data.wallet.WalletKeyReader.MaterialResult.Success) {
                        return@forEach
                    }
                    val words = readResult.mnemonic?.split(" ")
                    if (words.isNullOrEmpty()) return@forEach

                    candidates.sortedBy { it.accountIndex }.forEach { candidate ->
                        walletRepository.createSubAccount(
                            parentWalletId = parentId,
                            name = "Sub-account ${candidate.accountIndex}",
                            parentMnemonic = words,
                            explicitIndex = candidate.accountIndex,
                            persistKeys = { newWalletId, bundle ->
                                walletKeyWriter.persistNewWallet(
                                    activity = activity,
                                    walletId = newWalletId,
                                    bundle = bundle,
                                    walletType = com.rjnr.pocketnode.data.wallet.KeyManager.WALLET_TYPE_MNEMONIC,
                                    mnemonicBackedUp = false,
                                    promptTitle = "Secure restored sub-account",
                                    promptSubtitle = "Encrypt sub-account ${candidate.accountIndex}'s keys.",
                                )
                            },
                        ).onSuccess { wallet ->
                            restored++
                            gatewayRepository.onActiveWalletChanged(wallet)
                        }.onFailure { e ->
                            Log.e(TAG, "Restore failed for $parentId index ${candidate.accountIndex}", e)
                        }
                    }
                }
                if (restored == 0) {
                    _uiState.update {
                        it.copy(
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(
                                "Could not restore sub-accounts. Authenticate and try again."
                            )
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isRestoring = false) }
            }
        }
    }

    fun switchWallet(walletId: String) {
        viewModelScope.launch {
            try {
                walletRepository.switchActiveWallet(walletId)
                val wallet = walletRepository.getById(walletId) ?: return@launch
                gatewayRepository.onActiveWalletChanged(wallet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch wallet", e)
                _uiState.update {
                    it.copy(
                        error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                            com.rjnr.pocketnode.R.string.vm_error_switch_wallet_failed,
                            listOf(e.message ?: ""),
                        ),
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class WalletManagerUiState(
    val walletGroups: List<WalletGroup> = emptyList(),
    /** Discovered-but-unrestored sub-account slots with on-chain history (#82). */
    val foundCandidates: List<com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity> = emptyList(),
    val isRestoring: Boolean = false,
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
)
