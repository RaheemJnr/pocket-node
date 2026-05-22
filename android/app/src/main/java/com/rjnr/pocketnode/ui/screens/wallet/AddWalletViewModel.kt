package com.rjnr.pocketnode.ui.screens.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.util.Bip39WordList
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddWalletUiState(
    val isLoading: Boolean = false,
    val name: String = "",
    val importWords: List<String> = List(12) { "" },
    val importSuggestions: Map<Int, List<String>> = emptyMap(),
    val importWordErrors: Set<Int> = emptySet(),
    val importPrivateKey: String = "",
    val createdWallet: WalletEntity? = null,
    val isNewlyGenerated: Boolean = false,
    val error: UiMessage? = null,
    val parentWallets: List<WalletEntity> = emptyList(),
    val selectedParentId: String? = null
)

@HiltViewModel
class AddWalletViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository,
    private val mnemonicManager: MnemonicManager
) : ViewModel() {

    /**
     * Optional parent-wallet hint forwarded from the WalletManager
     * per-row "Add" button (Telegram bug 2). Null means "user opened
     * the Add screen from the FAB", in which case we land on the mode
     * picker. Non-null means "user wanted to add a sub-account to this
     * specific parent", and we jump straight to the sub-account form
     * with the parent pre-selected.
     */
    val preselectedParentId: String? = savedStateHandle["parentId"]

    private val _uiState = MutableStateFlow(AddWalletUiState())
    val uiState: StateFlow<AddWalletUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val mnemonicRoots = walletRepository.getAll()
                .filter { it.type == "mnemonic" && it.parentWalletId == null }
            _uiState.update {
                it.copy(
                    parentWallets = mnemonicRoots,
                    // Pre-select the parent if the route arg pointed at one
                    // that still exists. Stale arg (wallet deleted between
                    // navigation and arrival) silently falls back to no
                    // selection so the user lands on the parent picker.
                    selectedParentId = preselectedParentId
                        ?.takeIf { id -> mnemonicRoots.any { p -> p.walletId == id } },
                )
            }
        }
    }

    fun selectParent(walletId: String) {
        _uiState.update { it.copy(selectedParentId = walletId) }
    }

    fun createSubAccount() {
        if (_uiState.value.isLoading) return // prevent double-tap
        val name = _uiState.value.name.trim()
        val parentId = _uiState.value.selectedParentId

        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }
        if (parentId == null) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_select_parent_wallet)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                walletRepository.createSubAccount(parentId, name)
            }.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message?.let(UiMessage::Raw)) }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateImportWord(index: Int, text: String) {
        val trimmed = text.trim().lowercase()
        val newWords = _uiState.value.importWords.toMutableList().apply { set(index, trimmed) }
        val newSuggestions = _uiState.value.importSuggestions.toMutableMap()
        val newErrors = _uiState.value.importWordErrors.toMutableSet()

        if (trimmed.length >= 2) {
            newSuggestions[index] = Bip39WordList.getSuggestions(trimmed)
        } else {
            newSuggestions.remove(index)
        }

        if (trimmed.isNotEmpty() && !Bip39WordList.isValidWord(trimmed)) {
            newErrors.add(index)
        } else {
            newErrors.remove(index)
        }

        _uiState.update {
            it.copy(
                importWords = newWords,
                importSuggestions = newSuggestions,
                importWordErrors = newErrors
            )
        }
    }

    fun selectImportSuggestion(index: Int, word: String) {
        val newWords = _uiState.value.importWords.toMutableList().apply { set(index, word) }
        val newSuggestions = _uiState.value.importSuggestions.toMutableMap().apply { remove(index) }
        val newErrors = _uiState.value.importWordErrors.toMutableSet().apply { remove(index) }
        _uiState.update {
            it.copy(
                importWords = newWords,
                importSuggestions = newSuggestions,
                importWordErrors = newErrors
            )
        }
    }

    fun pasteImportMnemonic(text: String) {
        val parts = text.trim().lowercase().split("\\s+".toRegex()).take(12)
        val newWords = List(12) { i -> parts.getOrElse(i) { "" } }
        val newErrors = newWords.mapIndexedNotNull { i, w ->
            if (w.isNotEmpty() && !Bip39WordList.isValidWord(w)) i else null
        }.toSet()
        _uiState.update {
            it.copy(
                importWords = newWords,
                importSuggestions = emptyMap(),
                importWordErrors = newErrors
            )
        }
    }

    fun updateImportPrivateKey(key: String) {
        _uiState.update { it.copy(importPrivateKey = key) }
    }

    fun createNewWallet() {
        if (_uiState.value.isLoading) return // prevent double-tap
        val name = _uiState.value.name.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }

        // Wallet count is no longer capped at creation time (#118). The cap is
        // applied at sync-registration time only — `registerAllWalletScripts`
        // takes the first MAX_CONCURRENT_WALLET_SCRIPTS under the ALL_WALLETS
        // strategy. Users can create as many wallets as they want; only the
        // first N stay actively synced when ALL_WALLETS is selected.
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                walletRepository.createWallet(name)
            }.onSuccess { (wallet, _) ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet, isNewlyGenerated = true) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message?.let(UiMessage::Raw)) }
            }
        }
    }

    fun importMnemonic() {
        if (_uiState.value.isLoading) return // prevent double-tap
        val name = _uiState.value.name.trim()
        val words = _uiState.value.importWords.map { it.trim().lowercase() }

        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }
        if (words.any { it.isEmpty() }) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_fill_all_words)) }
            return
        }
        if (words.any { !Bip39WordList.isValidWord(it) }) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_words_not_bip39)) }
            return
        }
        if (!mnemonicManager.validateMnemonic(words)) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_invalid_mnemonic)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                walletRepository.importWallet(name, words)
            }.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message?.let(UiMessage::Raw)) }
            }
        }
    }

    fun importRawKey() {
        if (_uiState.value.isLoading) return // prevent double-tap
        val name = _uiState.value.name.trim()
        val key = _uiState.value.importPrivateKey.trim()

        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }
        if (key.removePrefix("0x").length != 64) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_invalid_private_key)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                walletRepository.importRawKey(name, key)
            }.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message?.let(UiMessage::Raw)) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
