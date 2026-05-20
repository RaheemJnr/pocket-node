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
    private val gatewayRepository: GatewayRepository
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
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
)
