package com.rjnr.pocketnode.ui.screens.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.Logger
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.util.Bip39WordList
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.data.restorehint.RestoreHintImporter
import com.rjnr.pocketnode.data.restorehint.RestoreHintPlan
import com.rjnr.pocketnode.data.restorehint.RestoreHintSecret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    val createdWallet: WalletEntity? = null,
    val isNewlyGenerated: Boolean = false,
    val error: UiMessage? = null,
    val parentWallets: List<WalletEntity> = emptyList(),
    val selectedParentId: String? = null,
    /** #431: post-import sync-start picker, shown after a successful mnemonic
     * or raw-key import (not after creating a fresh wallet or sub-account,
     * which already default correctly to NEW_WALLET / the parent's window). */
    val showSyncModeDialog: Boolean = false,
    /** #431: an Apply from the sync sheet is in flight; the sheet disables
     * its buttons and further submissions are ignored until it resolves. */
    val isApplyingSyncChoice: Boolean = false,
    /** #431: why the last Apply failed, shown inside the sheet (a snackbar
     * would sit under the sheet's window). Cleared on the next Apply. */
    val syncChoiceError: UiMessage? = null,
    val tipBlockNumber: Long = 0L,
    /** #559: a restore hint verified against the just-imported secret, awaiting confirmation. */
    val restoreHintPlan: RestoreHintPlan? = null,
    /** #559: why the restore hint file was rejected, shown on the form and in the sheet. */
    val restoreHintError: RestoreHintImporter.Reason? = null,
    /** #559: a restore hint file was picked on the import form and will be checked after import. */
    val hasRestoreHintFile: Boolean = false,
    /**
     * #563: Cancel on the post-import sheet removes the wallet just added and
     * returns to the previously active one ("Cancel adding wallet"). False
     * when there is no previous wallet or a rollback already failed; Cancel
     * then keeps the wallet, as dismissing the sheet does.
     */
    val cancelRemovesWallet: Boolean = false,
)

@HiltViewModel
class AddWalletViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository,
    private val mnemonicManager: MnemonicManager,
    private val walletKeyReader: WalletKeyReader,
    private val walletKeyWriter: WalletKeyWriter,
    private val authManager: com.rjnr.pocketnode.data.auth.AuthManager,
    private val logger: Logger,
    private val restoreHintImporter: RestoreHintImporter,
    private val importOperations: com.rjnr.pocketnode.ui.security.WalletImportOperations,
) : ViewModel() {

    /** Every fresh namespace requires verified independent passphrase protection. */
    private suspend fun persistWalletKeys(
        activity: FragmentActivity,
        walletId: WalletRepository.Creation,
        bundle: com.rjnr.pocketnode.data.crypto.WalletKeyBundle,
        walletType: String,
        mnemonicBackedUp: Boolean,
        promptTitle: String = "Secure wallet",
        promptSubtitle: String = "Protect this wallet with your independent wallet passphrase.",
    ): WalletKeyWriter.Result = walletKeyWriter.persistCreatedWallet(
        activity, walletId, bundle, walletType, mnemonicBackedUp, promptTitle, promptSubtitle,
    )

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

    // #431: the wallet a mnemonic/raw-key import just produced, held back from
    // `createdWallet` (which drives screen navigation) until the sync-mode
    // sheet resolves, either an explicit pick or a dismiss.
    private var pendingImportedWallet: WalletEntity? = null

    // #563: the wallet that was active before the pending import, restored
    // if the user cancels the sync choice. Null when there was none.
    private var previousActiveWalletId: String? = null
    private var importOperation: kotlinx.coroutines.Job? = null
    private var registeredImportId: String? = null

    private suspend fun ensureImportRegistered(wallet: WalletEntity?) {
        if (wallet == null) return
        importOperation?.join()
        check(walletRepository.getActive()?.walletId == wallet.walletId) { "Active wallet changed. Reopen this wallet to sync." }
        if (registeredImportId == wallet.walletId) return
        gatewayRepository.onActiveWalletChanged(wallet)
        registeredImportId = wallet.walletId
    }

    /**
     * #559: the restore hint picked on the import form, as text. It is picked
     * BEFORE the import so no secret is ever held while the file picker is
     * open (opening it can trigger the re-auth lock). Not secret, and kept
     * across a lock for that reason.
     */
    private var restoreHintFileText: String? = null

    /** #559: the verified hint awaiting confirmation; public data only. */
    private var pendingRestoreHint: RestoreHintImporter.Verification.Ready? = null

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
        viewModelScope.launch {
            gatewayRepository.syncProgress.collect { progress ->
                _uiState.update { it.copy(tipBlockNumber = progress.tipBlockNumber) }
            }
        }
    }

    /**
     * Apply the user's post-import sync-mode pick. Called unconditionally
     * (RECENT included), the wallet may have imported at a different
     * default, so short-circuiting RECENT can silently drop the choice.
     */
    fun onSyncModeSelected(mode: SyncMode, customHeight: Long?) = applySyncChoice(mode, customHeight)

    private fun applySyncChoice(
        mode: SyncMode,
        customHeight: Long?,
        beforeResync: suspend () -> Unit = {},
    ) {
        // A second Apply tap while the first is in flight must not launch a
        // second resync: each would publish createdWallet, and a later one
        // could publish null over the first and strand navigation.
        if (_uiState.value.isApplyingSyncChoice) return
        // Taken once here. Null only if the sheet shows without a pending
        // import; the choice is then still applied and the sheet closes.
        val wallet = pendingImportedWallet
        _uiState.update {
            it.copy(isApplyingSyncChoice = true, syncChoiceError = null, restoreHintError = null)
        }
        viewModelScope.launch {
            // resyncAccount reports failure through its Result; the catch is
            // a backstop so an unexpected throw still clears the in-flight
            // flag instead of leaving Apply disabled for good.
            val failure = try {
                ensureImportRegistered(wallet)
                beforeResync()
                gatewayRepository.resyncAccount(mode, customHeight).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                pendingImportedWallet = null
                previousActiveWalletId = null
                clearRestoreHint()
                _uiState.update {
                    it.copy(
                        isApplyingSyncChoice = false,
                        showSyncModeDialog = false,
                        cancelRemovesWallet = false,
                        createdWallet = wallet,
                    )
                }
            } else {
                // Keep the sheet open and the wallet pending so the user can
                // retry or dismiss; the mode was not applied.
                logger.e(TAG, "Post-import sync mode change failed", failure)
                _uiState.update {
                    it.copy(
                        isApplyingSyncChoice = false,
                        syncChoiceError = UiMessage.Resource(
                            R.string.vm_error_sync_mode_change_failed,
                            listOf(failure.message ?: ""),
                        ),
                    )
                }
            }
        }
    }

    /** Dismissing the sheet leaves the RECENT default from import in place. */
    fun skipSyncSelection() {
        if (_uiState.value.isApplyingSyncChoice) return
        val wallet = pendingImportedWallet
        _uiState.update { it.copy(isApplyingSyncChoice = true) }
        viewModelScope.launch {
            try {
                ensureImportRegistered(wallet)
                pendingImportedWallet = null
                previousActiveWalletId = null
                clearRestoreHint()
                _uiState.update { it.copy(showSyncModeDialog = false, syncChoiceError = null, cancelRemovesWallet = false, createdWallet = wallet) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(syncChoiceError = UiMessage.Raw("Could not register this wallet. Retry syncing.")) } }
            finally { _uiState.update { it.copy(isApplyingSyncChoice = false) } }
        }
    }

    /**
     * #563: Cancel on the post-import sheet. Removes the wallet this import
     * just added (only its rows and keys) and moves the app back onto the
     * previously active wallet through the normal switch path. The user stays
     * on the Add Wallet screen. If the removal fails, the wallet stays added
     * and the sheet says so; a further Cancel then keeps it.
     */
    fun cancelAddedWallet() {
        if (_uiState.value.isApplyingSyncChoice) return
        val wallet = pendingImportedWallet
        val previousId = previousActiveWalletId
        if (wallet == null || previousId == null || !_uiState.value.cancelRemovesWallet) {
            skipSyncSelection()
            return
        }
        _uiState.update {
            it.copy(isApplyingSyncChoice = true, syncChoiceError = null, restoreHintError = null)
        }
        viewModelScope.launch {
            importOperation?.cancel()
            importOperation?.join()
            // #563 review F2: once the rollback commits, the gateway must
            // switch back even if the user leaves the screen
            // (viewModelScope cancelled), or it stays on the removed wallet.
            val rolledBack = withContext(NonCancellable) {
                val removed = try {
                    walletRepository.rollbackAddedWallet(wallet, previousId)
                    true
                } catch (e: Exception) {
                    logger.e(TAG, "Rolling back the added wallet failed", e)
                    false
                }
                if (removed) {
                    // The removed wallet is the loaded one: this switches the
                    // gateway back to the previous wallet via onActiveWalletChanged.
                    try {
                        gatewayRepository.onWalletDeleted(wallet.walletId)
                    } catch (e: Exception) {
                        logger.w(TAG, "Re-registration after removing the added wallet failed", e)
                    }
                }
                removed
            }
            if (!rolledBack) {
                _uiState.update {
                    it.copy(
                        isApplyingSyncChoice = false,
                        cancelRemovesWallet = false,
                        syncChoiceError = UiMessage.Resource(R.string.add_wallet_rollback_failed),
                    )
                }
                return@launch
            }
            pendingImportedWallet = null
            previousActiveWalletId = null
            clearRestoreHint()
            // #563 review F6: the add was abandoned, so nothing the user
            // entered for it stays on screen: name, phrase words, private key.
            _uiState.update {
                it.copy(
                    isApplyingSyncChoice = false,
                    showSyncModeDialog = false,
                    syncChoiceError = null,
                    cancelRemovesWallet = false,
                    name = "",
                )
            }
        }
    }

    // -- Restore hint (#559) --

    /** The picked file's text, or null when it could not be read. */
    fun onRestoreHintFilePicked(fileText: String?) {
        restoreHintFileText = fileText
        _uiState.update {
            it.copy(
                hasRestoreHintFile = fileText != null,
                restoreHintError = if (fileText == null) RestoreHintImporter.Reason.UNREADABLE else null,
            )
        }
    }

    fun removeRestoreHintFile() = clearRestoreHint()

    private fun clearRestoreHint() {
        restoreHintFileText = null
        pendingRestoreHint = null
        _uiState.update { it.copy(hasRestoreHintFile = false, restoreHintPlan = null, restoreHintError = null) }
    }

    /**
     * Checks the picked hint against the secret the import just derived, and
     * wipes the secret before returning. A re-auth lock while the secret
     * existed discards the result: the lock is meant to end its life.
     */
    private fun acceptRestoreHint(verification: RestoreHintImporter.Verification?) {
        val state = _uiState.value
        if (!state.showSyncModeDialog || state.isApplyingSyncChoice) return
        when (verification) {
            is RestoreHintImporter.Verification.Ready -> {
                pendingRestoreHint = verification
                _uiState.update { it.copy(restoreHintPlan = verification.plan, restoreHintError = null) }
            }
            is RestoreHintImporter.Verification.Rejected -> _uiState.update { it.copy(restoreHintPlan = null, restoreHintError = verification.reason) }
            null -> Unit
        }
    }

    /** Applies the verified hint: seeds discovery, then the ordinary post-import resync at its start. */
    fun confirmRestoreHint() {
        val ready = pendingRestoreHint ?: return
        val walletId = pendingImportedWallet?.walletId ?: return
        _uiState.update { it.copy(restoreHintPlan = null) }
        applySyncChoice(ready.plan.syncMode, ready.plan.customHeight) {
            restoreHintImporter.prepare(walletId, ready)
        }
    }

    /** Back to the normal sync choices; the hint is not used. */
    fun dismissRestoreHint() {
        pendingRestoreHint = null
        _uiState.update { it.copy(restoreHintPlan = null) }
    }

    fun selectParent(walletId: String) {
        _uiState.update { it.copy(selectedParentId = walletId) }
    }

    /** Authenticates the parent seed, then independently protects the derived child. */
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
            try {
                val expectedParent = walletRepository.getById(parentId) ?: return@launch
                val lock = ReauthLockEvents.locks.value

                // Prompt #1: read parent's mnemonic.
                val readResult = walletKeyReader.readKeyMaterial(
                    activity = activity,
                    walletId = parentId,
                    promptTitle = "Unlock parent wallet",
                    promptSubtitle = "Authentication required to derive a sub-account.",
                )
                val parentMnemonic = when (readResult) {
                    is WalletKeyReader.MaterialResult.Success -> {
                        readResult.privateKey.fill(0)
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

                if (_uiState.value.selectedParentId != parentId || walletRepository.getById(parentId) != expectedParent) return@launch
                com.rjnr.pocketnode.ui.security.WalletDerivationSession.run(activity, lock) { session ->
                // Persist only against the parent authenticated above.
                // Distinct title/subtitle from prompt #1 so the user understands
                // they're securing the NEW sub-account, not re-confirming the parent.
                val result = walletRepository.createSubAccount(parentId, name, parentMnemonic, expectedParent = expectedParent) { walletId, bundle ->
                    session.persist {
                    persistWalletKeys(
                        activity = activity,
                        walletId = walletId,
                        bundle = bundle,
                        walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                        mnemonicBackedUp = false,
                        promptTitle = "Secure new sub-account",
                        promptSubtitle = "Protect the new account with your independent wallet passphrase.",
                    )
                    }
                }
                result.onSuccess { wallet ->
                    gatewayRepository.onActiveWalletChanged(wallet)
                    _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
                }.onFailure { error ->
                    logger.e(TAG, "Sub-account creation failed", error)
                    _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
                }
                }
            } finally { _uiState.update { it.copy(isLoading = false) } }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun createNewWallet(activity: FragmentActivity) {
        if (_uiState.value.isLoading) return // prevent double-tap
        val name = _uiState.value.name.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }

        // Wallet count is no longer capped at creation time (#118). The cap is
        // applied at sync-registration time only, `registerAllWalletScripts`
        // takes the first MAX_CONCURRENT_WALLET_SCRIPTS under the ALL_WALLETS
        // strategy. Users can create as many wallets as they want; only the
        // first N stay actively synced when ALL_WALLETS is selected.
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = walletRepository.createWallet(
                name = name,
                persistKeys = { walletId, bundle ->
                    persistWalletKeys(
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
                logger.e(TAG, "Wallet creation failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
            }
        }
    }

    fun importMnemonic(activity: FragmentActivity) = importWallet(activity, false)
    fun importRawKey(activity: FragmentActivity) = importWallet(activity, true)

    private fun importWallet(activity: FragmentActivity, privateKey: Boolean) {
        if (_uiState.value.isLoading || importOperation?.isActive == true) return
        val name = _uiState.value.name.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }
        registeredImportId = null
        importOperation = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                when (val result = importOperations.import(activity, privateKey, name, restoreHintFileText, gatewayRepository.currentNetwork,
                    onCommitted = { wallet, previous ->
                        pendingImportedWallet = wallet
                        previousActiveWalletId = previous?.takeIf { it != wallet.walletId }
                        _uiState.update { it.copy(isLoading = false, showSyncModeDialog = true, cancelRemovesWallet = previousActiveWalletId != null) }
                    }) { wallet, _ ->
                    gatewayRepository.onActiveWalletChanged(wallet)
                    registeredImportId = wallet.walletId
                }) {
                    is com.rjnr.pocketnode.ui.security.WalletImportOperations.Outcome.Completed -> acceptRestoreHint(result.hint)
                    is com.rjnr.pocketnode.ui.security.WalletImportOperations.Outcome.Failed -> _uiState.update { it.copy(error = UiMessage.Raw(result.message)) }
                    com.rjnr.pocketnode.ui.security.WalletImportOperations.Outcome.Cancelled -> Unit
                }
            } finally { _uiState.update { it.copy(isLoading = false) } }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /** See `OnboardingViewModel.persistErrorMessage`, same shape. */
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
                WalletKeyWriter.Result.NoSecureLock ->
                    UiMessage.Raw("Could not secure the wallet. Enable a screen lock in device settings and try again.")
                null -> error.message?.let(UiMessage::Raw)
                else -> error.message?.let(UiMessage::Raw)
            }
        }
    }
}
