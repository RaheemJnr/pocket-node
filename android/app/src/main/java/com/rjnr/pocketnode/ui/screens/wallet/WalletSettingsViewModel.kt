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
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
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
    private val walletKeyWriter: WalletKeyWriter,
    private val keyMaterialDao: KeyMaterialDao,
    private val migrationHelper: com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper,
    private val encryptionManager: com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager,
    private val authManager: com.rjnr.pocketnode.data.auth.AuthManager,
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
                _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_rename_failed, listOf(e.message ?: ""))) }
            }
        }
    }

    // -- Delete --

    fun requestDelete() {
        val wallet = _uiState.value.wallet ?: return
        // No more upfront active-wallet block. The confirmDelete path
        // auto-switches to a sibling before deleting (PR #273), but
        // this entry point was still gating on `wallet.isActive`, so
        // the user hit the dead-end snackbar before ever seeing the
        // confirmation dialog. Removed so the flow can reach
        // confirmDelete and use the auto-switch logic.
        // (Telegram bug 5 follow-up — original fix only covered half
        // the path.)
        viewModelScope.launch {
            val count = walletRepository.walletCount()
            if (count <= 1) {
                _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_delete_last)) }
                return@launch
            }

            // Parent wallet holds the mnemonic used to derive sub-accounts. Deleting it
            // removes the seed, so user can no longer recover or derive more sub-accounts
            // even though existing sub-accounts keep their own stored keys.
            val subAccounts = walletDao.getSubAccountsList(walletId)
            if (subAccounts.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        error = if (subAccounts.size == 1)
                            com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_delete_sub_accounts_first_one)
                        else
                            com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                com.rjnr.pocketnode.R.string.vm_error_delete_sub_accounts_first_other,
                                listOf(subAccounts.size),
                            ),
                    )
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
                val current = _uiState.value.wallet
                val isActive = current?.isActive == true ||
                    walletPreferences.getActiveWalletId() == walletId

                if (isActive) {
                    // Auto-switch to a sibling before delete. The previous
                    // behaviour was to refuse with "Cannot delete the active
                    // wallet" — a UX dead-end since the user can't tell what
                    // "switch first" means when they only see the one
                    // settings screen. (Telegram bug 5)
                    val candidates = walletRepository.getAll()
                        .filter { it.walletId != walletId }

                    if (candidates.isEmpty()) {
                        // Last wallet on the device. We do NOT delete here
                        // because doing so would leave the app in a no-
                        // wallet state without resetting the PIN, which is
                        // a worse trap than the original guard. Direct the
                        // user at the Forgot-PIN destructive recovery flow
                        // which handles wipe + PIN reset + process restart
                        // together. (Telegram bug 6)
                        _uiState.update {
                            it.copy(
                                showDeleteConfirm = false,
                                error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                    com.rjnr.pocketnode.R.string.vm_error_delete_last_wallet,
                                ),
                            )
                        }
                        return@launch
                    }

                    // Prefer a parent (top-level) wallet so the user doesn't
                    // end up on an orphaned-feeling sub-account, but fall
                    // back to any sibling if all that's left are sub-
                    // accounts of other parents.
                    val next = candidates.firstOrNull { it.parentWalletId == null }
                        ?: candidates.first()
                    walletRepository.switchActiveWallet(next.walletId)
                }

                walletRepository.deleteWallet(walletId)
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete wallet", e)
                _uiState.update {
                    it.copy(showDeleteConfirm = false, error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_delete_failed, listOf(e.message ?: "")))
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
                    it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_reopen_for_biometric))
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
            // Lazy V1 → V2 migration on first sensitive-data reveal.
            // Previously the V1 branch fell through to the silent PIN-
            // only load, which a Telegram user reported as "mnemonic
            // readily available" (bug 1). The wallet stays on V1 until
            // the user re-launches the app and triggers AuthScreen's
            // migration runner, which is a long window where the
            // mnemonic is one PIN entry from disclosure.
            //
            // The flow now: one BiometricPrompt unlocks a V2 encrypt
            // cipher; we use it to migrate the row to V2 AND extract
            // the plaintext bundle in the same cipher operation. After
            // this method returns once, the wallet is on V2 and all
            // future reveals follow the standard V2 read path with its
            // own BiometricPrompt.
            if (kdf == 1) {
                val cipher = try {
                    encryptionManager.newEncryptCipherV2()
                } catch (e: Throwable) {
                    Log.e(TAG, "V2 cipher creation failed", e)
                    _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_wallet_key, listOf(e.message ?: "cipher"))) }
                    return@launch
                }
                val auth = authManager.authenticateForCipher(
                    activity = activity,
                    cipher = cipher,
                    title = "View recovery phrase",
                    subtitle = "Verify your identity to display this wallet's secrets",
                )
                when (auth) {
                    is com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Cancelled -> {
                        _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_auth_cancelled)) }
                        return@launch
                    }
                    is com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Error -> {
                        _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_wallet_key, listOf("auth ${auth.errorCode}"))) }
                        return@launch
                    }
                    is com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Success -> {
                        val bundle = migrationHelper.migrateWalletAndExtract(walletId, auth.cipher)
                            .getOrElse { e ->
                                Log.e(TAG, "V1 → V2 migrate-and-extract failed for $walletId", e)
                                _uiState.update {
                                    it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_wallet_key, listOf(e.message ?: "migrate")))
                                }
                                return@launch
                            }
                        val words = bundle.mnemonic?.split(" ")
                        _uiState.update {
                            it.copy(
                                privateKeyHex = bundle.privateKeyHex,
                                mnemonicWords = words,
                                seedPhraseUnlocked = true,
                            )
                        }
                        return@launch
                    }
                }
            }
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
                    _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_auth_cancelled)) }
                }
                is WalletKeyReader.MaterialResult.NotAvailable -> {
                    _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_wallet_key, listOf(result.reason))) }
                }
                is WalletKeyReader.MaterialResult.KeyInvalidated -> {
                    _uiState.update {
                        it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_biometric_changed_self))
                    }
                }
                is WalletKeyReader.MaterialResult.Success -> {
                    val keyHex = result.privateKey.joinToString("") { "%02x".format(it) }
                    val words = result.mnemonic?.split(" ")
                    // seedPhraseUnlocked must flip here so the screen
                    // gate (`showSeedPhrase && (seedPhraseUnlocked || !requiresPinForSeedPhrase())`)
                    // shows the mnemonic. Previously this flag was set
                    // only via onPinVerified, so V2 reveals worked by
                    // accident only when no PIN was set.
                    _uiState.update { it.copy(privateKeyHex = keyHex, mnemonicWords = words, seedPhraseUnlocked = true) }
                }
            }
        }
    }

    // -- Sub-accounts --

    /**
     * V2-aware sub-account creation. Two BiometricPrompts:
     *
     *   1. Read parent's mnemonic via [WalletKeyReader] (one prompt).
     *   2. Persist new sub-account at V2 via [WalletKeyWriter.persistNewWallet]
     *      inside the [WalletRepository.createSubAccount] callback (second
     *      prompt).
     *
     * Replaces the old V1-fallback path which crashed on V2 parents.
     */
    fun addSubAccount(activity: FragmentActivity, name: String) {
        viewModelScope.launch {
            when (val readResult = walletKeyReader.readKeyMaterial(
                activity = activity,
                walletId = walletId,
                promptTitle = "Authenticate to add account",
                promptSubtitle = "Verify your identity to derive a new sub-account",
            )) {
                is WalletKeyReader.MaterialResult.Cancelled,
                is WalletKeyReader.MaterialResult.AuthError ->
                    _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_auth_cancelled)) }
                is WalletKeyReader.MaterialResult.NotAvailable ->
                    _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_parent, listOf(readResult.reason))) }
                is WalletKeyReader.MaterialResult.KeyInvalidated ->
                    _uiState.update {
                        it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_biometric_changed_parent))
                    }
                is WalletKeyReader.MaterialResult.Success -> {
                    val words = readResult.mnemonic?.split(" ")
                    if (words.isNullOrEmpty()) {
                        _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_parent_no_mnemonic)) }
                        return@launch
                    }
                    // Distinct prompt copy for prompt #2 (sub-account encrypt)
                    // so the user knows this is securing the NEW sub-account,
                    // not re-confirming the parent unlock from prompt #1.
                    val result = walletRepository.createSubAccount(walletId, name, parentMnemonic = words) { newWalletId, bundle ->
                        walletKeyWriter.persistNewWallet(
                            activity = activity,
                            walletId = newWalletId,
                            bundle = bundle,
                            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                            mnemonicBackedUp = false,
                            promptTitle = "Secure new sub-account",
                            promptSubtitle = "Encrypt the new account's keys.",
                        )
                    }
                    result.onFailure { e ->
                        // Cancelled at the persist prompt: silent. Other errors: surface.
                        val isCancelled = e is WalletKeyWriter.PersistException
                            && e.result is WalletKeyWriter.Result.Cancelled
                        if (!isCancelled) {
                            Log.e(TAG, "Failed to create V2 sub-account", e)
                            _uiState.update {
                                it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                    com.rjnr.pocketnode.R.string.vm_error_create_account_failed,
                                    listOf(e.message ?: "")
                                ))
                            }
                        }
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
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    val seedPhraseUnlocked: Boolean = false,
    val privateKeyHex: String? = null,
    val mnemonicWords: List<String>? = null
)
