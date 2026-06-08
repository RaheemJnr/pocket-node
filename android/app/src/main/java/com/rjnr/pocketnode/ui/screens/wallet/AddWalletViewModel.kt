package com.rjnr.pocketnode.ui.screens.wallet

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
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

private const val TAG = "AddWalletVM"

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
    private val mnemonicManager: MnemonicManager,
    private val walletKeyReader: WalletKeyReader,
    private val walletKeyWriter: WalletKeyWriter,
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

    /**
     * V2-aware sub-account creation. Two BiometricPrompt prompts fire,
     * back-to-back:
     *
     *   1. Read parent's mnemonic via [WalletKeyReader.readKeyMaterial]
     *      (bonus bug fix — the previous flow routed through V1 storage
     *      and crashed on V2 parents).
     *   2. Encrypt + persist the new sub-account's key material via
     *      [WalletKeyWriter.persistNewWallet] (inside [persistKeys]).
     */
    fun createSubAccount(activity: FragmentActivity) {
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

            // Prompt #1: read parent's mnemonic.
            val readResult = walletKeyReader.readKeyMaterial(
                activity = activity,
                walletId = parentId,
                promptTitle = "Unlock parent wallet",
                promptSubtitle = "Authentication required to derive a sub-account.",
            )
            val parentMnemonic = when (readResult) {
                is WalletKeyReader.MaterialResult.Success -> {
                    val words = readResult.mnemonic?.split(" ")
                    if (words.isNullOrEmpty()) {
                        _uiState.update {
                            it.copy(isLoading = false, error = UiMessage.Resource(R.string.vm_error_parent_no_mnemonic))
                        }
                        return@launch
                    }
                    words
                }
                is WalletKeyReader.MaterialResult.Cancelled -> {
                    // Silent: user dismissed prompt intentionally.
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                is WalletKeyReader.MaterialResult.AuthError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = UiMessage.Raw("Auth error: ${readResult.message}"))
                    }
                    return@launch
                }
                is WalletKeyReader.MaterialResult.KeyInvalidated -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = UiMessage.Resource(R.string.vm_error_biometric_changed_parent))
                    }
                    return@launch
                }
                is WalletKeyReader.MaterialResult.NotAvailable -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = UiMessage.Resource(R.string.vm_error_cannot_read_parent, listOf(readResult.reason)),
                        )
                    }
                    return@launch
                }
            }

            // Prompt #2: persist sub-account at V2 (inside the closure).
            // Distinct title/subtitle from prompt #1 so the user understands
            // they're securing the NEW sub-account, not re-confirming the parent.
            val result = walletRepository.createSubAccount(parentId, name, parentMnemonic) { walletId, bundle ->
                walletKeyWriter.persistNewWallet(
                    activity = activity,
                    walletId = walletId,
                    bundle = bundle,
                    walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                    mnemonicBackedUp = false,
                    promptTitle = "Secure new sub-account",
                    promptSubtitle = "Encrypt the new account's keys.",
                )
            }
            result.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                Log.e(TAG, "Sub-account creation failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
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

    fun createNewWallet(activity: FragmentActivity) {
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
            val result = walletRepository.createWallet(
                name = name,
                persistKeys = { walletId, bundle ->
                    walletKeyWriter.persistNewWallet(
                        activity = activity,
                        walletId = walletId,
                        bundle = bundle,
                        walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                        mnemonicBackedUp = false,
                    )
                },
            )
            result.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update {
                    it.copy(isLoading = false, createdWallet = wallet, isNewlyGenerated = true)
                }
            }.onFailure { error ->
                Log.e(TAG, "Wallet creation failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
            }
        }
    }

    fun importMnemonic(activity: FragmentActivity) {
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
            val result = walletRepository.importFromMnemonic(
                words = words,
                name = name,
                persistKeys = { walletId, bundle ->
                    walletKeyWriter.persistNewWallet(
                        activity = activity,
                        walletId = walletId,
                        bundle = bundle,
                        walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                        mnemonicBackedUp = true,
                    )
                },
            )
            result.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                Log.e(TAG, "Mnemonic import failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
            }
        }
    }

    fun importRawKey(activity: FragmentActivity) {
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
            val result = walletRepository.importRawKey(key, name) { walletId, bundle ->
                walletKeyWriter.persistNewWallet(
                    activity = activity,
                    walletId = walletId,
                    bundle = bundle,
                    walletType = KeyManager.WALLET_TYPE_RAW_KEY,
                    mnemonicBackedUp = false,
                )
            }
            result.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                Log.e(TAG, "Raw key import failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /** See `OnboardingViewModel.persistErrorMessage` — same shape. */
        internal fun persistErrorMessage(error: Throwable): UiMessage? {
            val pex = error as? WalletKeyWriter.PersistException
            return when (val r = pex?.result) {
                WalletKeyWriter.Result.Cancelled -> null
                is WalletKeyWriter.Result.AuthError ->
                    UiMessage.Raw("Auth error: ${r.message}")
                is WalletKeyWriter.Result.WriteFailed ->
                    UiMessage.Raw("Failed to save wallet: ${r.cause.message ?: "unknown error"}")
                WalletKeyWriter.Result.KeyInvalidated ->
                    UiMessage.Raw("Wallet keys must be re-imported")
                null -> error.message?.let(UiMessage::Raw)
                else -> error.message?.let(UiMessage::Raw)
            }
        }
    }
}
