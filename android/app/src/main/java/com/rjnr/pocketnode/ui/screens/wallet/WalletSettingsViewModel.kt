package com.rjnr.pocketnode.ui.screens.wallet

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val TAG = "WalletSettingsVM"

@HiltViewModel
class WalletSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val pinManager: PinManager,
    private val daoCellDao: DaoCellDao,
    private val transactionDao: TransactionDao,
    private val walletPreferences: WalletPreferences,
    private val walletKeyReader: WalletKeyReader,
    private val keyMaterialDao: KeyMaterialDao,
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"] ?: ""

    private val _uiState = MutableStateFlow(WalletSettingsUiState())
    val uiState: StateFlow<WalletSettingsUiState> = _uiState.asStateFlow()

    init {
        loadWallet()
        observeSubAccounts()
    }

    private fun loadWallet() {
        viewModelScope.launch {
            val wallet = walletRepository.getById(walletId)
            val isBackedUp = if (wallet != null) {
                keyManager.hasMnemonicBackupForWallet(walletId)
            } else false
            _uiState.update {
                it.copy(
                    wallet = wallet,
                    editName = wallet?.name ?: "",
                    isBackedUp = isBackedUp
                )
            }
        }
    }

    private fun observeSubAccounts() {
        viewModelScope.launch {
            walletDao.getSubAccounts(walletId).collect { subAccounts ->
                _uiState.update { it.copy(subAccounts = subAccounts) }
            }
        }
    }

    // -- Name editing --

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, editName = it.wallet?.name ?: "") }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false, editName = it.wallet?.name ?: "") }
    }

    fun updateEditName(name: String) {
        _uiState.update { it.copy(editName = name) }
    }

    fun saveName() {
        val name = _uiState.value.editName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                walletRepository.renameWallet(walletId, name)
                loadWallet()
                _uiState.update { it.copy(isEditing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to rename: ${e.message}") }
            }
        }
    }

    // -- Delete --

    fun requestDelete() {
        val wallet = _uiState.value.wallet ?: return
        if (wallet.isActive) {
            _uiState.update { it.copy(error = "Cannot delete the active wallet. Switch to another wallet first.") }
            return
        }
        viewModelScope.launch {
            val count = walletRepository.walletCount()
            if (count <= 1) {
                _uiState.update { it.copy(error = "Cannot delete the last wallet. Create another wallet first.") }
                return@launch
            }

            // Parent wallet holds the mnemonic used to derive sub-accounts. Deleting it
            // removes the seed, so user can no longer recover or derive more sub-accounts
            // even though existing sub-accounts keep their own stored keys.
            val subAccounts = walletDao.getSubAccountsList(walletId)
            if (subAccounts.isNotEmpty()) {
                _uiState.update {
                    it.copy(error = "This wallet has ${subAccounts.size} sub-account${if (subAccounts.size > 1) "s" else ""}. Delete them first before removing the parent.")
                }
                return@launch
            }

            // Check for active DAO deposits
            val network = walletPreferences.getSelectedNetwork().name
            val daoDeposits = daoCellDao.getActiveByWalletAndNetwork(walletId, network)
            val hasDaoDeposits = daoDeposits.isNotEmpty()
            val daoAmount = if (hasDaoDeposits) {
                val totalShannons = daoDeposits.sumOf { it.capacity }
                String.format(Locale.US, "%,.2f", totalShannons / 100_000_000.0)
            } else ""

            // Check for pending transactions
            val pendingTxs = transactionDao.getPendingByWallet(walletId, network)
            val pendingTxCount = pendingTxs.size

            _uiState.update {
                it.copy(
                    showDeleteConfirm = true,
                    hasDaoDeposits = hasDaoDeposits,
                    daoDepositAmount = daoAmount,
                    pendingTxCount = pendingTxCount
                )
            }
        }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            try {
                walletRepository.deleteWallet(walletId)
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete wallet", e)
                _uiState.update {
                    it.copy(showDeleteConfirm = false, error = "Delete failed: ${e.message}")
                }
            }
        }
    }

    // -- Wallet type helpers --

    fun hasMnemonic(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.type == KeyManager.WALLET_TYPE_MNEMONIC && wallet.parentWalletId == null
    }

    fun isRawKey(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.type == KeyManager.WALLET_TYPE_RAW_KEY
    }

    fun isSubAccount(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.parentWalletId != null
    }

    fun getPrivateKeyHex(): String? = _uiState.value.privateKeyHex

    fun requiresPinForSeedPhrase(): Boolean = pinManager.hasPin()

    fun onPinVerified() {
        _uiState.update { it.copy(seedPhraseUnlocked = true) }
        loadSensitiveData()
    }

    fun lockSeedPhrase() {
        _uiState.update { it.copy(seedPhraseUnlocked = false, privateKeyHex = null, mnemonicWords = null) }
    }

    fun getMnemonic(): List<String>? = _uiState.value.mnemonicWords

    /**
     * Load private key and mnemonic from Room into UiState (V1 path).
     * Called when seed phrase is unlocked (PIN verified or no PIN required).
     * For V2 wallets the activity-aware overload [loadSensitiveData(activity)]
     * must be used — otherwise the V2 read throws and the seed phrase view
     * shows blank fields (#213 sub-PR 5).
     */
    fun loadSensitiveData() {
        viewModelScope.launch {
            if (keyMaterialDao.getKdfVersion(walletId) == 2) {
                _uiState.update {
                    it.copy(error = "Reopen this screen — biometric unlock required for V2 wallets")
                }
                return@launch
            }
            try {
                val keyHex = keyManager.getPrivateKeyForWallet(walletId)?.let { bytes ->
                    bytes.joinToString("") { "%02x".format(it) }
                }
                val words = try {
                    keyManager.getMnemonicForWallet(walletId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get mnemonic", e)
                    null
                }
                _uiState.update { it.copy(privateKeyHex = keyHex, mnemonicWords = words) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sensitive data", e)
            }
        }
    }

    /**
     * V2-aware variant of [loadSensitiveData]. For V2 wallets, drives a
     * BiometricPrompt CryptoObject via [WalletKeyReader] to decrypt the
     * bundled key+mnemonic. For V1 wallets, falls back to the silent
     * path so existing PIN-only flows keep working. The mnemonic is
     * pulled from the same V2 bundle, so V2 reads cost exactly one prompt.
     */
    fun loadSensitiveData(activity: FragmentActivity) {
        viewModelScope.launch {
            val kdf = keyMaterialDao.getKdfVersion(walletId)
            if (kdf != 2) {
                loadSensitiveData()
                return@launch
            }
            // V2 path: single BiometricPrompt CryptoObject unlocks the
            // bundle that contains both the private key and the mnemonic.
            // No second prompt needed for the mnemonic display.
            when (val result = walletKeyReader.readKeyMaterial(
                activity = activity,
                walletId = walletId,
                promptTitle = "View recovery phrase",
                promptSubtitle = "Verify your identity to display this wallet's secrets",
            )) {
                is WalletKeyReader.MaterialResult.Cancelled,
                is WalletKeyReader.MaterialResult.AuthError -> {
                    _uiState.update { it.copy(error = "Authentication cancelled") }
                }
                is WalletKeyReader.MaterialResult.NotAvailable -> {
                    _uiState.update { it.copy(error = "Cannot read wallet key: ${result.reason}") }
                }
                is WalletKeyReader.MaterialResult.Success -> {
                    val keyHex = result.privateKey.joinToString("") { "%02x".format(it) }
                    val words = result.mnemonic?.split(" ")
                    _uiState.update { it.copy(privateKeyHex = keyHex, mnemonicWords = words) }
                }
            }
        }
    }

    // -- Sub-accounts --

    fun addSubAccount(name: String) {
        viewModelScope.launch {
            try {
                walletRepository.createSubAccount(walletId, name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create sub-account", e)
                _uiState.update { it.copy(error = "Failed to create account: ${e.message}") }
            }
        }
    }

    /**
     * V2-aware sub-account creation. For V2 parent wallets, reads the
     * parent's mnemonic via [WalletKeyReader] (one BiometricPrompt
     * CryptoObject), then passes it as `parentMnemonicOverride` to
     * [WalletRepository.createSubAccount] so the repository doesn't
     * attempt a second (silent, failing) read.
     */
    fun addSubAccount(activity: FragmentActivity, name: String) {
        viewModelScope.launch {
            val kdf = keyMaterialDao.getKdfVersion(walletId)
            if (kdf != 2) {
                addSubAccount(name)
                return@launch
            }
            when (val result = walletKeyReader.readKeyMaterial(
                activity = activity,
                walletId = walletId,
                promptTitle = "Authenticate to add account",
                promptSubtitle = "Verify your identity to derive a new sub-account",
            )) {
                is WalletKeyReader.MaterialResult.Cancelled,
                is WalletKeyReader.MaterialResult.AuthError ->
                    _uiState.update { it.copy(error = "Authentication cancelled") }
                is WalletKeyReader.MaterialResult.NotAvailable ->
                    _uiState.update { it.copy(error = "Cannot read parent wallet: ${result.reason}") }
                is WalletKeyReader.MaterialResult.Success -> {
                    val words = result.mnemonic?.split(" ")
                    if (words.isNullOrEmpty()) {
                        _uiState.update { it.copy(error = "Parent wallet has no mnemonic") }
                        return@launch
                    }
                    try {
                        walletRepository.createSubAccount(walletId, name, parentMnemonicOverride = words)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create V2 sub-account", e)
                        _uiState.update { it.copy(error = "Failed to create account: ${e.message}") }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class WalletSettingsUiState(
    val wallet: WalletEntity? = null,
    val subAccounts: List<WalletEntity> = emptyList(),
    val isEditing: Boolean = false,
    val editName: String = "",
    val isBackedUp: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val hasDaoDeposits: Boolean = false,
    val daoDepositAmount: String = "",
    val pendingTxCount: Int = 0,
    val deleted: Boolean = false,
    val error: String? = null,
    val seedPhraseUnlocked: Boolean = false,
    val privateKeyHex: String? = null,
    val mnemonicWords: List<String>? = null
)
