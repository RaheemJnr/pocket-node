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
    val words: List<String> = List(12) { "" },
    val suggestions: Map<Int, List<String>> = emptyMap(),
    val wordErrors: Set<Int> = emptySet(),
    val isImporting: Boolean = false,
    val importSuccess: Boolean = false,
    val showPrivateKeyDialog: Boolean = false,
    val showSyncModeDialog: Boolean = false,
    /** #431: an Apply from the sync sheet is in flight; see onSyncModeSelected. */
    val isApplyingSyncChoice: Boolean = false,
    /** #431: why the last Apply failed, shown inside the sheet. */
    val syncChoiceError: String? = null,
    val tipBlockNumber: Long = 0L,
    val error: String? = null,
    /** #559: a restore hint verified against the just-imported secret, awaiting confirmation. */
    val restoreHintPlan: RestoreHintPlan? = null,
    /** #559: why the picked restore hint file was rejected, shown inside the sheet. */
    val restoreHintError: RestoreHintImporter.Reason? = null,
    /** #559: true once the imported secret is held for verifying a hint. */
    val canUseRestoreHint: Boolean = false,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(MnemonicImportUiState())
    val uiState: StateFlow<MnemonicImportUiState> = _uiState.asStateFlow()

    /**
     * #559: the just-imported wallet's secret (BIP-39 seed or private key),
     * held only while the post-import sync sheet is open so a restore hint
     * can be verified against it. Wiped when the sheet resolves, on a lock
     * and when the ViewModel is cleared.
     */
    private var restoreHintSecret: RestoreHintSecret? = null
    private var importedWalletId: String? = null

    init {
        // #524: a typed recovery phrase never survives a lock.
        viewModelScope.onEachReauthLock {
            releaseRestoreHintSecret()
            _uiState.update {
                it.copy(words = List(12) { "" }, suggestions = emptyMap(), wordErrors = emptySet())
            }
        }
        viewModelScope.launch {
            repository.syncProgress.collect { progress ->
                _uiState.update { it.copy(tipBlockNumber = progress.tipBlockNumber) }
            }
        }
    }

    fun updateWord(index: Int, text: String) {
        val trimmed = text.trim().lowercase()
        val newWords = _uiState.value.words.toMutableList().apply { set(index, trimmed) }
        val newSuggestions = _uiState.value.suggestions.toMutableMap()
        val newErrors = _uiState.value.wordErrors.toMutableSet()

        if (trimmed.length >= 2) {
            newSuggestions[index] = Bip39WordList.getSuggestions(trimmed)
        } else {
            newSuggestions.remove(index)
        }

        // Mark error if user finished typing (no suggestions match exactly) and word is invalid
        if (trimmed.isNotEmpty() && !Bip39WordList.isValidWord(trimmed)) {
            newErrors.add(index)
        } else {
            newErrors.remove(index)
        }

        _uiState.update {
            it.copy(words = newWords, suggestions = newSuggestions, wordErrors = newErrors)
        }
    }

    fun selectSuggestion(index: Int, word: String) {
        val newWords = _uiState.value.words.toMutableList().apply { set(index, word) }
        val newSuggestions = _uiState.value.suggestions.toMutableMap().apply { remove(index) }
        val newErrors = _uiState.value.wordErrors.toMutableSet().apply { remove(index) }
        _uiState.update {
            it.copy(words = newWords, suggestions = newSuggestions, wordErrors = newErrors)
        }
    }

    fun pasteMnemonic(text: String) {
        val parts = text.trim().lowercase().split("\\s+".toRegex()).take(12)
        val newWords = List(12) { i -> parts.getOrElse(i) { "" } }
        val newErrors = newWords.mapIndexedNotNull { i, w ->
            if (w.isNotEmpty() && !Bip39WordList.isValidWord(w)) i else null
        }.toSet()
        _uiState.update {
            it.copy(words = newWords, suggestions = emptyMap(), wordErrors = newErrors)
        }
    }

    fun importMnemonic(activity: FragmentActivity) {
        val words = _uiState.value.words.map { it.trim().lowercase() }

        if (words.any { it.isEmpty() }) {
            _uiState.update { it.copy(error = "Please fill in all 12 words") }
            return
        }

        if (!mnemonicManager.validateMnemonic(words)) {
            _uiState.update { it.copy(error = "Invalid mnemonic. Please check your words and try again.") }
            return
        }

        // Same V1 software-only fallback as OnboardingViewModel.createNewWallet.
        // The Welcome screen surfaced an informed-consent "Continue without a
        // device lock?" dialog before navigating here, so the user has
        // already opted in. AuthScreen migration loop auto-upgrades the V1
        // row to V2 when they enable a device lock later (#289 follow-up).
        val useV1Fallback = !canCreateV2BoundKey()
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null) }
            val result = walletRepository.importFromMnemonic(
                words = words,
                name = "Imported Wallet",
                persistKeys = { walletId, bundle ->
                    if (useV1Fallback) {
                        walletKeyWriter.persistNewWalletV1Fallback(
                            walletId = walletId,
                            bundle = bundle,
                            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                            mnemonicBackedUp = true,
                        )
                    } else {
                        walletKeyWriter.persistNewWallet(
                            activity = activity,
                            walletId = walletId,
                            bundle = bundle,
                            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                            // The user just typed the seed phrase in, so by definition
                            // they hold a copy of it (or know where it is). Skip the
                            // post-onboarding backup nag.
                            mnemonicBackedUp = true,
                        )
                    }
                },
            )
            result.onSuccess { entity ->
                logger.d(TAG, "Imported wallet entity: ${entity.walletId}")
                repository.onActiveWalletChanged(entity)
                // #431: offer the sync-start picker after every import, on both
                // networks, it used to be mainnet-only, so a testnet restore
                // silently kept the RECENT default with no way to widen it here.
                _uiState.update {
                    it.copy(isImporting = false, showSyncModeDialog = true)
                }
                holdRestoreHintSecret(entity.walletId) {
                    withContext(Dispatchers.Default) { RestoreHintSecret.fromMnemonic(words) }
                }
            }.onFailure { error ->
                logger.e(TAG, "Mnemonic import failed", error)
                val msg = persistErrorMessageRaw(error)
                _uiState.update { it.copy(isImporting = false, error = msg) }
            }
        }
    }

    fun showPrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = true) }
    }

    fun hidePrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = false) }
    }

    fun importPrivateKey(activity: FragmentActivity, hex: String) {
        val useV1Fallback = !canCreateV2BoundKey()
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, showPrivateKeyDialog = false, error = null) }
            val result = walletRepository.importRawKey(hex, "Imported Wallet") { walletId, bundle ->
                if (useV1Fallback) {
                    walletKeyWriter.persistNewWalletV1Fallback(
                        walletId = walletId,
                        bundle = bundle,
                        walletType = KeyManager.WALLET_TYPE_RAW_KEY,
                        mnemonicBackedUp = false,
                    )
                } else {
                    walletKeyWriter.persistNewWallet(
                        activity = activity,
                        walletId = walletId,
                        bundle = bundle,
                        walletType = KeyManager.WALLET_TYPE_RAW_KEY,
                        mnemonicBackedUp = false,
                    )
                }
            }
            result.onSuccess { entity ->
                logger.d(TAG, "Imported raw key wallet entity: ${entity.walletId}")
                repository.onActiveWalletChanged(entity)
                // #431: same as the mnemonic path, always offer the picker.
                _uiState.update {
                    it.copy(isImporting = false, showSyncModeDialog = true)
                }
                holdRestoreHintSecret(entity.walletId) {
                    val keyBytes = hex.removePrefix("0x").hexToByteArray()
                    try {
                        RestoreHintSecret.fromPrivateKey(keyBytes)
                    } finally {
                        keyBytes.fill(0)
                    }
                }
            }.onFailure { error ->
                logger.e(TAG, "Private key import failed", error)
                val msg = persistErrorMessageRaw(error)
                _uiState.update { it.copy(isImporting = false, error = msg) }
            }
        }
    }

    /**
     * Same check as OnboardingViewModel.canCreateV2BoundKey: V2 Keystore key
     * can only be minted on a device with an enrolled biometric OR a device
     * lock (PIN/pattern/password). Returning false routes the import through
     * the V1 software-only fallback.
     */
    private fun canCreateV2BoundKey(): Boolean =
        authManager.isBiometricEnrolled() || authManager.hasDeviceCredential()

    companion object {
        /**
         * Same mapping logic as `OnboardingViewModel.persistErrorMessage`
         * but returns a raw String for the legacy `error: String?` ui-state
         * shape used by this screen. Cancelled is silent (returns null).
         */
        internal fun persistErrorMessageRaw(error: Throwable): String? {
            val pex = error as? WalletKeyWriter.PersistException
            return when (val r = pex?.result) {
                WalletKeyWriter.Result.Cancelled -> null
                is WalletKeyWriter.Result.AuthError -> "Auth error: ${r.message}"
                is WalletKeyWriter.Result.WriteFailed ->
                    "Failed to save wallet: ${r.cause.message ?: "unknown error"}"
                WalletKeyWriter.Result.KeyInvalidated -> "Wallet keys must be re-imported"
                null -> error.message
                else -> error.message
            }
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
            beforeResync()
            // #431: apply unconditionally, including RECENT. The old
            // `if (mode != RECENT)` short-circuit assumed RECENT was
            // already registered, but the wallet may have imported with
            // a different default (or the user is switching back to
            // RECENT from another pick in this same sheet), skipping
            // the call silently dropped the choice.
            // resyncAccount reports failure through its Result; the catch is
            // a backstop so an unexpected throw still clears the in-flight flag.
            val failure = try {
                repository.resyncAccount(mode, customHeight).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                releaseRestoreHintSecret()
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
        releaseRestoreHintSecret()
        _uiState.update { it.copy(showSyncModeDialog = false, syncChoiceError = null, importSuccess = true) }
    }

    // -- Restore hint (#559) --

    private suspend fun holdRestoreHintSecret(walletId: String, make: suspend () -> RestoreHintSecret) {
        releaseRestoreHintSecret()
        val secret = try {
            make()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The hint is optional: without the secret the button stays hidden.
            logger.w(TAG, "Restore hint secret unavailable", e)
            return
        }
        // The user may have resolved the sheet while the seed was derived.
        val state = _uiState.value
        if (!state.showSyncModeDialog || state.isApplyingSyncChoice) {
            secret.wipe()
            return
        }
        restoreHintSecret = secret
        importedWalletId = walletId
        _uiState.update { it.copy(canUseRestoreHint = true) }
    }

    private fun releaseRestoreHintSecret() {
        restoreHintSecret?.wipe()
        restoreHintSecret = null
        importedWalletId = null
        _uiState.update {
            it.copy(canUseRestoreHint = false, restoreHintPlan = null, restoreHintError = null)
        }
    }

    /** The picked file's text, or null when it could not be read. */
    fun onRestoreHintPicked(fileText: String?) {
        val secret = restoreHintSecret
        if (_uiState.value.isApplyingSyncChoice || secret == null) return
        val verification = if (fileText == null) {
            RestoreHintImporter.Verification.Rejected(RestoreHintImporter.Reason.UNREADABLE)
        } else {
            restoreHintImporter.verify(fileText, secret, repository.currentNetwork)
        }
        _uiState.update {
            when (verification) {
                is RestoreHintImporter.Verification.Ready ->
                    it.copy(restoreHintPlan = verification.plan, restoreHintError = null, syncChoiceError = null)
                is RestoreHintImporter.Verification.Rejected ->
                    it.copy(restoreHintPlan = null, restoreHintError = verification.reason)
            }
        }
    }

    /** Applies the verified hint: seeds discovery, then the ordinary post-import resync at its start. */
    fun confirmRestoreHint() {
        val plan = _uiState.value.restoreHintPlan ?: return
        val secret = restoreHintSecret ?: return
        val walletId = importedWalletId ?: return
        _uiState.update { it.copy(restoreHintPlan = null) }
        applySyncChoice(plan.syncMode, plan.customHeight) {
            restoreHintImporter.prepare(walletId, plan, secret)
        }
    }

    fun dismissRestoreHint() {
        _uiState.update { it.copy(restoreHintPlan = null) }
    }

    override fun onCleared() {
        restoreHintSecret?.wipe()
        restoreHintSecret = null
        super.onCleared()
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
    val clipboardManager = LocalClipboardManager.current

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

    // Private key import dialog
    if (uiState.showPrivateKeyDialog) {
        PrivateKeyImportDialog(
            onDismiss = { viewModel.hidePrivateKeyImport() },
            onImport = { viewModel.importPrivateKey(activity, it) }
        )
    }

    val pickRestoreHint = rememberRestoreHintPicker { text -> viewModel.onRestoreHintPicked(text) }
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
            onUseRestoreHint = if (uiState.canUseRestoreHint) pickRestoreHint else null,
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

            // Paste button
            OutlinedButton(
                onClick = {
                    clipboardManager.getText()?.text?.let { text ->
                        viewModel.pasteMnemonic(text)
                    }
                },
                modifier = Modifier.fillMaxWidth().uaTestTag("import-paste")
            ) {
                Icon(Lucide.ClipboardPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mnemonic_import_paste))
            }

            // Adaptive word grid: 2 columns at typical phone widths (≈160dp each),
            // 3+ on Medium/Expanded so foldable inner displays don't waste space.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(12) { index ->
                    MnemonicWordInput(
                        index = index,
                        value = uiState.words[index],
                        suggestions = uiState.suggestions[index] ?: emptyList(),
                        isError = uiState.wordErrors.contains(index),
                        onValueChange = { viewModel.updateWord(index, it) },
                        onSuggestionSelected = { viewModel.selectSuggestion(index, it) }
                    )
                }
            }

            // Private key fallback
            TextButton(
                onClick = { viewModel.showPrivateKeyImport() },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.mnemonic_import_have_private_key))
            }

            // Heads-up that a SYSTEM credential sheet is about to appear , 
            // users restoring a seed didn't know which password the
            // "Secure wallet" prompt wanted (knmo, Nervos Talk, 2026-06).
            Text(
                text = stringResource(R.string.mnemonic_import_screen_lock_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(4.dp))

            // Import button
            Button(
                onClick = { viewModel.importMnemonic(activity) },
                modifier = Modifier.fillMaxWidth().uaTestTag("import-submit"),
                enabled = !uiState.isImporting && uiState.words.all { it.isNotBlank() }
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

@Composable
private fun PrivateKeyImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var privateKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mnemonic_import_private_key_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your 64-character private key (hex) to restore your wallet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it.trim() },
                    label = { Text(stringResource(R.string.mnemonic_import_private_key_label)) },
                    placeholder = { Text(stringResource(R.string.mnemonic_import_private_key_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(privateKey) },
                enabled = privateKey.length >= 64
            ) {
                Text(stringResource(R.string.mnemonic_import_private_key_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mnemonic_import_cancel))
            }
        }
    )
}
