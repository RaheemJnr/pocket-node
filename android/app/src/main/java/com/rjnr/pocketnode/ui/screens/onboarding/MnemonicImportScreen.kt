package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.uaTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.components.MnemonicWordInput
import com.rjnr.pocketnode.ui.components.SyncOptionsSheet
import com.rjnr.pocketnode.ui.util.Bip39WordList
import dagger.hilt.android.lifecycle.HiltViewModel
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.data.restorehint.RestoreHintImporter
import com.rjnr.pocketnode.data.restorehint.RestoreHintPlan
import com.rjnr.pocketnode.data.restorehint.RestoreHintSecret
import com.rjnr.pocketnode.ui.components.RestoreHintFileRow
import com.rjnr.pocketnode.ui.components.RestoreHintReadyDialog
import com.rjnr.pocketnode.ui.components.messageRes
import com.rjnr.pocketnode.ui.components.rememberRestoreHintPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// -- ViewModel --

data class MnemonicImportUiState(
    val isImporting: Boolean = false,
    val importSuccess: Boolean = false,
    val showSyncModeDialog: Boolean = false,
    /** #431: an Apply from the sync sheet is in flight; see onSyncModeSelected. */
    val isApplyingSyncChoice: Boolean = false,
    /** #431: why the last Apply failed, shown inside the sheet. */
    val syncChoiceError: String? = null,
    val tipBlockNumber: Long = 0L,
    val error: String? = null,
    /** #559: a restore hint verified against the just-imported secret, awaiting confirmation. */
    val restoreHintPlan: RestoreHintPlan? = null,
    /** #559: why the restore hint file was rejected, shown on the screen and in the sheet. */
    val restoreHintError: RestoreHintImporter.Reason? = null,
    /** #559: a restore hint file was picked on this screen and will be checked after import. */
    val hasRestoreHintFile: Boolean = false,
)

private const val TAG = "MnemonicImportVM"

@HiltViewModel
class MnemonicImportViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val mnemonicManager: MnemonicManager,
    private val walletRepository: WalletRepository,
    private val walletKeyWriter: WalletKeyWriter,
    private val authManager: com.rjnr.pocketnode.data.auth.AuthManager,
    private val logger: Logger,
    private val restoreHintImporter: RestoreHintImporter,
    private val importOperations: com.rjnr.pocketnode.ui.security.WalletImportOperations,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MnemonicImportUiState())
    val uiState: StateFlow<MnemonicImportUiState> = _uiState.asStateFlow()

    /**
     * #559: the restore hint picked on this screen, as text. It is picked
     * BEFORE the import so no secret is ever held while the file picker is
     * open (opening it can trigger the re-auth lock). Not secret, and kept
     * across a lock for that reason.
     */
    private var restoreHintFileText: String? = null

    /** #559: the verified hint awaiting confirmation; public data only. */
    private var pendingRestoreHint: RestoreHintImporter.Verification.Ready? = null
    private var importedWalletId: String? = null

    init {
        viewModelScope.launch {
            repository.syncProgress.collect { progress ->
                _uiState.update { it.copy(tipBlockNumber = progress.tipBlockNumber) }
            }
        }
    }

    private var importOperation: kotlinx.coroutines.Job? = null
    private var registeredImportId: String? = null

    private suspend fun ensureImportRegistered() {
        val id = importedWalletId ?: return
        importOperation?.join()
        val wallet = walletRepository.getActive()
        check(wallet?.walletId == id) { "Active wallet changed. Reopen this wallet to sync." }
        if (registeredImportId == id) return
        repository.onActiveWalletChanged(wallet!!)
        registeredImportId = id
    }

    fun importMnemonic(activity: FragmentActivity) = importWallet(activity, false)
    fun importPrivateKey(activity: FragmentActivity) = importWallet(activity, true)

    private fun importWallet(activity: FragmentActivity, privateKey: Boolean) {
        if (_uiState.value.isImporting || importOperation?.isActive == true) return
        registeredImportId = null
        importOperation = viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null) }
            try {
                when (val result = importOperations.import(activity, privateKey, "Imported Wallet", restoreHintFileText, repository.currentNetwork,
                    onCommitted = { wallet, _ ->
                        importedWalletId = wallet.walletId
                        _uiState.update { it.copy(isImporting = false, showSyncModeDialog = true) }
                    }) { wallet, _ ->
                    repository.onActiveWalletChanged(wallet)
                    registeredImportId = wallet.walletId
                }) {
                    is com.rjnr.pocketnode.ui.security.WalletImportOperations.Outcome.Completed -> acceptRestoreHint(result.hint)
                    is com.rjnr.pocketnode.ui.security.WalletImportOperations.Outcome.Failed -> _uiState.update { it.copy(error = result.message) }
                    com.rjnr.pocketnode.ui.security.WalletImportOperations.Outcome.Cancelled -> Unit
                }
            } finally { _uiState.update { it.copy(isImporting = false) } }
        }
    }

    fun onSyncModeSelected(mode: SyncMode, customHeight: Long?) = applySyncChoice(mode, customHeight)

    private fun applySyncChoice(
        mode: SyncMode,
        customHeight: Long?,
        beforeResync: suspend () -> Unit = {},
    ) {
        // Ignore a second Apply tap while the first resync is in flight.
        if (_uiState.value.isApplyingSyncChoice) return
        _uiState.update {
            it.copy(isApplyingSyncChoice = true, syncChoiceError = null, restoreHintError = null)
        }
        viewModelScope.launch {
            // #431: apply unconditionally, including RECENT. The old
            // `if (mode != RECENT)` short-circuit assumed RECENT was
            // already registered, but the wallet may have imported with
            // a different default (or the user is switching back to
            // RECENT from another pick in this same sheet), skipping
            // the call silently dropped the choice.
            // resyncAccount reports failure through its Result; the catch is
            // a backstop so an unexpected throw still clears the in-flight flag.
            val failure = try {
                ensureImportRegistered()
                beforeResync()
                repository.resyncAccount(mode, customHeight).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                clearRestoreHint()
                _uiState.update {
                    it.copy(isApplyingSyncChoice = false, showSyncModeDialog = false, importSuccess = true)
                }
            } else {
                // Keep the sheet open so the user can retry or dismiss
                // (dismiss keeps the RECENT default from import).
                logger.e(TAG, "Post-import sync mode change failed", failure)
                _uiState.update {
                    it.copy(isApplyingSyncChoice = false, syncChoiceError = "Sync mode change failed: ${failure.message}")
                }
            }
        }
    }

    fun skipSyncSelection() {
        if (_uiState.value.isApplyingSyncChoice) return
        _uiState.update { it.copy(isApplyingSyncChoice = true) }
        viewModelScope.launch {
            try {
                ensureImportRegistered()
                clearRestoreHint()
                _uiState.update { it.copy(showSyncModeDialog = false, syncChoiceError = null, importSuccess = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(syncChoiceError = "Could not register this wallet. Retry syncing.") } }
            finally { _uiState.update { it.copy(isApplyingSyncChoice = false) } }
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
        importedWalletId = null
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
        val walletId = importedWalletId ?: return
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

// -- Screen --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicImportScreen(
    onNavigateToHome: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MnemonicImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE: secret material (mnemonic / raw key) is entered or shown
    // on this screen, block screenshots, screen recording, and the recents
    // thumbnail (#317).
    val secureView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val window = (secureView.context as? android.app.Activity)?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // MainActivity extends FragmentActivity; required to drive the
    // V2 BiometricPrompt CryptoObject flow on import (#289).
    val activity = androidx.compose.ui.platform.LocalContext.current as FragmentActivity

    LaunchedEffect(uiState.importSuccess) {
        if (uiState.importSuccess) onNavigateToHome()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    val pickRestoreHint = rememberRestoreHintPicker { text -> viewModel.onRestoreHintFilePicked(text) }
    val hintPlan = uiState.restoreHintPlan
    if (uiState.showSyncModeDialog && hintPlan != null) {
        RestoreHintReadyDialog(
            plan = hintPlan,
            onConfirm = { viewModel.confirmRestoreHint() },
            onDismiss = { viewModel.dismissRestoreHint() },
        )
    }

    // Post-import sync mode dialog: shown after every import, both networks.
    // Hidden while the restore hint confirmation is up, which replaces it.
    if (uiState.showSyncModeDialog && hintPlan == null) {
        SyncOptionsSheet(
            currentMode = SyncMode.RECENT,
            title = stringResource(R.string.home_post_import_sync_title),
            description = stringResource(R.string.home_post_import_sync_description),
            availableModes = listOf(
                SyncMode.NEW_WALLET, SyncMode.RECENT, SyncMode.FULL_HISTORY, SyncMode.CUSTOM
            ),
            onDismiss = { viewModel.skipSyncSelection() },
            onSelectMode = { mode, height -> viewModel.onSyncModeSelected(mode, height) },
            // Help icons are intentionally hidden in the post-import flow:
            // re-opening the sheet from an EducationSheet stack is risky in
            // this constrained context, so we suppress the affordance entirely.
            onTopicHelp = {},
            showHelpIcons = false,
            tipBlockNumber = uiState.tipBlockNumber,
            isApplying = uiState.isApplyingSyncChoice,
            errorText = uiState.syncChoiceError
                ?: uiState.restoreHintError?.let { stringResource(it.messageRes()) },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mnemonic_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.ChevronLeft, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Enter your 12-word recovery phrase to restore your wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // #559: picked before Import, so no secret is held while the
            // file picker (and the re-auth lock it can trigger) is open.
            RestoreHintFileRow(
                hasFile = uiState.hasRestoreHintFile,
                error = uiState.restoreHintError.takeIf { !uiState.showSyncModeDialog },
                onPick = pickRestoreHint,
                onRemove = { viewModel.removeRestoreHintFile() },
            )

            // Private key fallback
            TextButton(
                onClick = { viewModel.importPrivateKey(activity) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.mnemonic_import_have_private_key))
            }

            // Heads-up that a SYSTEM credential sheet is about to appear ,
            // users restoring a seed didn't know which password the
            // "Secure wallet" prompt wanted (knmo, Nervos Talk, 2026-06).
            Text(
                text = "Your independent wallet passphrase protects this wallet. Device authentication is optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(4.dp))

            // Import button
            Button(
                onClick = { viewModel.importMnemonic(activity) },
                modifier = Modifier.fillMaxWidth().uaTestTag("import-submit"),
                enabled = !uiState.isImporting
            ) {
                if (uiState.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.mnemonic_import_action))
            }
        }
    }
}
