package com.rjnr.pocketnode.ui.screens.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.AppStatePreferences
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

private const val TAG = "WalletSettingsVM"

@HiltViewModel
class WalletSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository,
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val daoCellDao: DaoCellDao,
    private val transactionDao: TransactionDao,
    private val appStatePreferences: AppStatePreferences,
    private val networkPreferences: NetworkPreferences,
    private val walletKeyReader: WalletKeyReader,
    private val walletKeyWriter: WalletKeyWriter,
    private val authManager: com.rjnr.pocketnode.data.auth.AuthManager,
    private val logger: Logger,
    private val backupOperations: com.rjnr.pocketnode.ui.security.WalletBackupOperations,
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"] ?: ""

    private val _uiState = MutableStateFlow(WalletSettingsUiState())
    val uiState: StateFlow<WalletSettingsUiState> = _uiState.asStateFlow()

    init {
        // #524: a revealed seed phrase or private key never survives a lock.
        viewModelScope.onEachReauthLock { lockSeedPhrase() }
        _uiState.update { it.copy(network = networkPreferences.getSelectedNetwork()) }
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
            val network = networkPreferences.getSelectedNetwork().name
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
                    appStatePreferences.getActiveWalletId() == walletId

                var nextActiveId: String? = null
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
                    nextActiveId = next.walletId
                }

                // #563 review F2: once the switch or the delete commits, the
                // gateway must follow even if the user leaves the screen
                // (viewModelScope cancelled) mid-way, or it stays on the
                // deleted wallet. Hence NonCancellable for the whole sequence.
                var cleanupPending = false
                withContext(NonCancellable) {
                    nextActiveId?.let { walletRepository.switchActiveWallet(it) }
                    try {
                        walletRepository.deleteWallet(walletId)
                    } catch (_: com.rjnr.pocketnode.data.wallet.WalletCleanupPendingException) {
                        cleanupPending = true
                    }
                    // #563: the switch only changed Room. Move the gateway
                    // (Home, Settings, sync registration) onto the active
                    // wallet now, after the delete, so no set includes the
                    // deleted one. The delete itself succeeded, so a
                    // registration failure here is logged, not reported as a
                    // failed delete.
                    try {
                        gatewayRepository.onWalletDeleted(walletId)
                    } catch (e: Exception) {
                        logger.w(TAG, "Re-registration after deleting $walletId failed", e)
                    }
                }
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = !cleanupPending, cleanupPending = cleanupPending) }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to delete wallet", e)
                _uiState.update {
                    it.copy(showDeleteConfirm = false, error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_delete_failed, listOf(e.message ?: "")))
                }
            }
        }
    }

    fun acknowledgeCleanupPending() {
        _uiState.update { if (it.cleanupPending) it.copy(cleanupPending = false, deleted = true) else it }
    }

    // -- Wallet type helpers --

    fun hasMnemonic(): Boolean = _uiState.value.hasMnemonic == true && _uiState.value.wallet?.parentWalletId == null
    fun isRawKey(): Boolean = _uiState.value.hasMnemonic == false
    fun isSubAccount(): Boolean = _uiState.value.wallet?.parentWalletId != null

    private var revealOperation: kotlinx.coroutines.Job? = null
    fun lockSeedPhrase() { revealOperation?.cancel() }

    /** Compatibility callbacks fail closed: an app PIN and an Activity-free call never read material. */
    fun onPinVerified() = Unit
    fun loadSensitiveData() {
        _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw("Authenticate in the foreground to view recovery material.")) }
    }

    fun loadSensitiveData(activity: FragmentActivity) {
        if (revealOperation?.isActive == true) return
        revealOperation = viewModelScope.launch {
            when (val result = backupOperations.show(activity, walletId, verify = false)) {
                is com.rjnr.pocketnode.ui.security.WalletBackupOperations.Outcome.Shown -> _uiState.update { it.copy(hasMnemonic = result.hasMnemonic) }
                is com.rjnr.pocketnode.ui.security.WalletBackupOperations.Outcome.Failed -> _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(result.message)) }
                com.rjnr.pocketnode.ui.security.WalletBackupOperations.Outcome.Cancelled -> Unit
            }
        }
    }

    // -- Sub-accounts --

    /** Uses only the authenticated parent seed and atomically binds the new child to that parent. */
    fun addSubAccount(activity: FragmentActivity, name: String) {
        viewModelScope.launch {
            com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) owned@ {
                val expectedParent = walletRepository.getById(walletId) ?: return@owned
                val lock = ReauthLockEvents.locks.value
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
                        readResult.privateKey.fill(0)
                        val words = readResult.mnemonic?.split(" ")
                        if (words.isNullOrEmpty()) {
                            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_parent_no_mnemonic)) }
                            return@owned
                        }
                        if (walletRepository.getById(walletId) != expectedParent) return@owned
                        com.rjnr.pocketnode.ui.security.WalletDerivationSession.run(activity, lock) { session ->
                        // Distinct prompt copy for prompt #2 (sub-account encrypt)
                        // so the user knows this is securing the NEW sub-account,
                        // not re-confirming the parent unlock from prompt #1.
                        val result = walletRepository.createSubAccount(walletId, name, parentMnemonic = words, expectedParent = expectedParent) { newWalletId, bundle ->
                            session.persist {

                                walletKeyWriter.persistCreatedWallet(
                                    activity = activity,
                                    walletId = newWalletId,
                                    bundle = bundle,
                                    walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                                    mnemonicBackedUp = false,
                                    promptTitle = "Secure new sub-account",
                                    promptSubtitle = "Encrypt the new account's keys.",
                                )
                            }
                        }
                        result.onFailure { e ->
                            // Cancelled at the persist prompt: silent. Other errors: surface.
                            val isCancelled = e is WalletKeyWriter.PersistException
                                && e.result is WalletKeyWriter.Result.Cancelled
                            if (!isCancelled) {
                                logger.e(TAG, "Failed to create V2 sub-account", e)
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
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

}

data class WalletSettingsUiState(
    val wallet: WalletEntity? = null,
    val subAccounts: List<WalletEntity> = emptyList(),
    /** Active network — sub-account rows encode their address with its hrp (#489). */
    val network: NetworkType = NetworkType.MAINNET,
    val isEditing: Boolean = false,
    val editName: String = "",
    val isBackedUp: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val hasDaoDeposits: Boolean = false,
    val daoDepositAmount: String = "",
    val pendingTxCount: Int = 0,
    val deleted: Boolean = false,
    val cleanupPending: Boolean = false,
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    val hasMnemonic: Boolean? = null,
)
