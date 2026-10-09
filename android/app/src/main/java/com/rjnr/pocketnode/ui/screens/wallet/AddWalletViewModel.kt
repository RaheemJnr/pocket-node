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
    /** F1/F3: show the no-device-lock informed-consent dialog before a V1 fallback write. */
    val showNoLockConsent: Boolean = false,
    val name: String = "",
    val importWords: List<String> = List(12) { "" },
    val importSuggestions: Map<Int, List<String>> = emptyMap(),
    val importWordErrors: Set<Int> = emptySet(),
    val importPrivateKey: String = "",
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
) : ViewModel() {

    /**
     * #354: a V2 auth-bound Keystore key needs an enrolled biometric or a
     * device credential. Without one, key creation throws
     * IllegalStateException("Secure lock screen must be enabled..."). The
     * main-wallet flow already falls back to a software-only V1 key in that
     * case; the add-wallet / sub-account flows did not, so they crashed.
     * Mirror the same gate here.
     */
    private fun canCreateV2BoundKey(): Boolean =
        authManager.isBiometricEnrolled() || authManager.hasDeviceCredential()

    // F1/F3: on a no-lock device, persisting drops to the V1 software fallback.
    // Onboarding warns before this; the Add Wallet flows must too. Each action
    // takes a `consented` flag: the first call, if no secure lock, stashes a
    // re-invocation and shows the shared consent dialog instead of silently
    // downgrading. Confirm re-runs the action with consented=true.
    private var pendingNoLockAction: (() -> Unit)? = null

    private fun requestNoLockConsent(action: () -> Unit) {
        pendingNoLockAction = action
        _uiState.update { it.copy(showNoLockConsent = true) }
    }

    fun confirmNoLockConsent() {
        val action = pendingNoLockAction
        pendingNoLockAction = null
        _uiState.update { it.copy(showNoLockConsent = false) }
        action?.invoke()
    }

    fun dismissNoLockConsent() {
        pendingNoLockAction = null
        _uiState.update { it.copy(showNoLockConsent = false, isLoading = false) }
    }

    /**
     * Persist a new wallet's keys, choosing the V1 software-only fallback when
     * the device has no secure lock (#354). AuthScreen's migration loop
     * upgrades the row to V2 once the user enables a device lock.
     */
    private suspend fun persistWalletKeys(
        activity: FragmentActivity,
        walletId: String,
        bundle: com.rjnr.pocketnode.data.crypto.WalletKeyBundle,
        walletType: String,
        mnemonicBackedUp: Boolean,
        promptTitle: String = "Secure wallet",
        promptSubtitle: String = "Use your phone's screen lock to encrypt the new wallet's keys.",
    ): WalletKeyWriter.Result =
        if (!canCreateV2BoundKey()) {
            walletKeyWriter.persistNewWalletV1Fallback(
                walletId = walletId,
                bundle = bundle,
                walletType = walletType,
                mnemonicBackedUp = mnemonicBackedUp,
            )
        } else {
            walletKeyWriter.persistNewWallet(
                activity = activity,
                walletId = walletId,
                bundle = bundle,
                walletType = walletType,
                mnemonicBackedUp = mnemonicBackedUp,
                promptTitle = promptTitle,
                promptSubtitle = promptSubtitle,
            )
        }

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

    /**
     * #559: the restore hint picked on the import form, as text. It is picked
     * BEFORE the import so no secret is ever held while the file picker is
     * open (opening it can trigger the re-auth lock). Not secret, and kept
     * across a lock for that reason.
     */
    private var restoreHintFileText: String? = null

    /** #559: the verified hint awaiting confirmation; public data only. */
    private var pendingRestoreHint: RestoreHintImporter.Verification.Ready? = null

    /** Seed derivation for hint verification, off the main thread; a test seam. */
    internal var deriveMnemonicHintSecret: suspend (List<String>) -> RestoreHintSecret = { words ->
        withContext(Dispatchers.Default) { RestoreHintSecret.fromMnemonic(words) }
    }

    init {
        // #524: a typed recovery phrase or private key never survives a lock.
        viewModelScope.onEachReauthLock {
            _uiState.update {
                it.copy(
                    importWords = List(12) { "" },
                    importSuggestions = emptyMap(),
                    importWordErrors = emptySet(),
                    importPrivateKey = "",
                )
            }
        }
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
            beforeResync()
            // resyncAccount reports failure through its Result; the catch is
            // a backstop so an unexpected throw still clears the in-flight
            // flag instead of leaving Apply disabled for good.
            val failure = try {
                gatewayRepository.resyncAccount(mode, customHeight).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                pendingImportedWallet = null
                clearRestoreHint()
                _uiState.update {
                    it.copy(isApplyingSyncChoice = false, showSyncModeDialog = false, createdWallet = wallet)
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
        // An Apply in flight owns the pending wallet and publishes it itself.
        if (_uiState.value.isApplyingSyncChoice) return
        val wallet = pendingImportedWallet
        pendingImportedWallet = null
        clearRestoreHint()
        _uiState.update { it.copy(showSyncModeDialog = false, syncChoiceError = null, createdWallet = wallet) }
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
    private suspend fun verifyRestoreHint(makeSecret: suspend () -> RestoreHintSecret) {
        val text = restoreHintFileText ?: return
        val locksAtStart = ReauthLockEvents.locks.value
        val secret = try {
            makeSecret()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Restore hint secret unavailable", e)
            return
        }
        val verification = try {
            if (ReauthLockEvents.locks.value != locksAtStart) return
            restoreHintImporter.verify(text, secret, gatewayRepository.currentNetwork)
        } finally {
            secret.wipe()
        }
        val state = _uiState.value
        if (ReauthLockEvents.locks.value != locksAtStart || !state.showSyncModeDialog || state.isApplyingSyncChoice) {
            return
        }
        when (verification) {
            is RestoreHintImporter.Verification.Ready -> {
                pendingRestoreHint = verification
                _uiState.update { it.copy(restoreHintPlan = verification.plan, restoreHintError = null) }
            }
            is RestoreHintImporter.Verification.Rejected ->
                _uiState.update { it.copy(restoreHintPlan = null, restoreHintError = verification.reason) }
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

    /**
     * V2-aware sub-account creation. Two BiometricPrompt prompts fire,
     * back-to-back:
     *
     *   1. Read parent's mnemonic via [WalletKeyReader.readKeyMaterial]
     *      (bonus bug fix, the previous flow routed through V1 storage
     *      and crashed on V2 parents).
     *   2. Encrypt + persist the new sub-account's key material via
     *      [WalletKeyWriter.persistNewWallet] (inside [persistKeys]).
     */
    fun createSubAccount(activity: FragmentActivity, consented: Boolean = false) {
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
        if (!consented && !canCreateV2BoundKey()) {
            requestNoLockConsent { createSubAccount(activity, consented = true) }
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
                persistWalletKeys(
                    activity = activity,
                    walletId = walletId,
                    bundle = bundle,
                    walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                    mnemonicBackedUp = false,
                    promptTitle = "Secure new sub-account",
                    promptSubtitle = "Use your phone's screen lock to encrypt the new account's keys.",
                )
            }
            result.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                logger.e(TAG, "Sub-account creation failed", error)
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

    fun createNewWallet(activity: FragmentActivity, consented: Boolean = false) {
        if (_uiState.value.isLoading) return // prevent double-tap
        val name = _uiState.value.name.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_enter_wallet_name)) }
            return
        }
        if (!consented && !canCreateV2BoundKey()) {
            requestNoLockConsent { createNewWallet(activity, consented = true) }
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

    fun importMnemonic(activity: FragmentActivity, consented: Boolean = false) {
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
        if (!consented && !canCreateV2BoundKey()) {
            requestNoLockConsent { importMnemonic(activity, consented = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = walletRepository.importFromMnemonic(
                words = words,
                name = name,
                persistKeys = { walletId, bundle ->
                    persistWalletKeys(
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
                // #431: hold navigation until the sync-mode sheet resolves.
                pendingImportedWallet = wallet
                _uiState.update { it.copy(isLoading = false, showSyncModeDialog = true) }
                verifyRestoreHint {
                    deriveMnemonicHintSecret(words)
                }
            }.onFailure { error ->
                logger.e(TAG, "Mnemonic import failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
            }
        }
    }

    fun importRawKey(activity: FragmentActivity, consented: Boolean = false) {
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
        if (!consented && !canCreateV2BoundKey()) {
            requestNoLockConsent { importRawKey(activity, consented = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = walletRepository.importRawKey(key, name) { walletId, bundle ->
                persistWalletKeys(
                    activity = activity,
                    walletId = walletId,
                    bundle = bundle,
                    walletType = KeyManager.WALLET_TYPE_RAW_KEY,
                    mnemonicBackedUp = false,
                )
            }
            result.onSuccess { wallet ->
                gatewayRepository.onActiveWalletChanged(wallet)
                // #431: hold navigation until the sync-mode sheet resolves.
                pendingImportedWallet = wallet
                _uiState.update { it.copy(isLoading = false, showSyncModeDialog = true) }
                verifyRestoreHint {
                    val keyBytes = key.hexToByteArray()
                    try {
                        RestoreHintSecret.fromPrivateKey(keyBytes)
                    } finally {
                        keyBytes.fill(0)
                    }
                }
            }.onFailure { error ->
                logger.e(TAG, "Raw key import failed", error)
                _uiState.update { it.copy(isLoading = false, error = persistErrorMessage(error)) }
            }
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
