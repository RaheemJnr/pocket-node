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
import com.rjnr.pocketnode.ui.util.uaTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.components.MnemonicWordInput
import com.rjnr.pocketnode.ui.components.SyncOptionsSheet
import com.rjnr.pocketnode.ui.util.Bip39WordList
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val tipBlockNumber: Long = 0L,
    val error: String? = null
)

private const val TAG = "MnemonicImportVM"

@HiltViewModel
class MnemonicImportViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val mnemonicManager: MnemonicManager,
    private val walletRepository: WalletRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MnemonicImportUiState())
    val uiState: StateFlow<MnemonicImportUiState> = _uiState.asStateFlow()

    init {
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

    fun importMnemonic() {
        val words = _uiState.value.words.map { it.trim().lowercase() }

        if (words.any { it.isEmpty() }) {
            _uiState.update { it.copy(error = "Please fill in all 12 words") }
            return
        }

        if (!mnemonicManager.validateMnemonic(words)) {
            _uiState.update { it.copy(error = "Invalid mnemonic. Please check your words and try again.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null) }
            try {
                // Import via WalletRepository so a Room entity is created
                val entity = walletRepository.importWallet("Imported Wallet", words)
                Log.d(TAG, "Imported wallet entity: ${entity.walletId}")
                repository.onActiveWalletChanged(entity)
                val showDialog = repository.currentNetwork == NetworkType.MAINNET
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importSuccess = !showDialog,
                        showSyncModeDialog = showDialog
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mnemonic import failed", e)
                _uiState.update { it.copy(isImporting = false, error = e.message) }
            }
        }
    }

    fun showPrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = true) }
    }

    fun hidePrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = false) }
    }

    fun importPrivateKey(hex: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, showPrivateKeyDialog = false, error = null) }
            try {
                // Import via WalletRepository so a Room entity is created
                val entity = walletRepository.importRawKey("Imported Wallet", hex)
                Log.d(TAG, "Imported raw key wallet entity: ${entity.walletId}")
                repository.onActiveWalletChanged(entity)
                val showDialog = repository.currentNetwork == NetworkType.MAINNET
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importSuccess = !showDialog,
                        showSyncModeDialog = showDialog
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Private key import failed", e)
                _uiState.update { it.copy(isImporting = false, error = e.message) }
            }
        }
    }

    fun onSyncModeSelected(mode: SyncMode, customHeight: Long?) {
        viewModelScope.launch {
            try {
                if (mode != SyncMode.RECENT) {
                    repository.resyncAccount(mode, customHeight)
                }
                _uiState.update { it.copy(showSyncModeDialog = false, importSuccess = true) }
            } catch (e: Exception) {
                // Still proceed with import — resync can be retried from Settings
                _uiState.update { it.copy(showSyncModeDialog = false, importSuccess = true, error = "Sync mode change failed: ${e.message}") }
            }
        }
    }

    fun skipSyncSelection() {
        _uiState.update { it.copy(showSyncModeDialog = false, importSuccess = true) }
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
            onImport = { viewModel.importPrivateKey(it) }
        )
    }

    // Post-import sync mode dialog (mainnet only)
    if (uiState.showSyncModeDialog) {
        SyncOptionsSheet(
            currentMode = SyncMode.RECENT,
            title = "Choose Sync Start Point",
            description = "Select how far back to sync your wallet history. If your wallet is older than 30 days, choose Custom to enter a specific block height.",
            availableModes = listOf(SyncMode.RECENT, SyncMode.CUSTOM),
            onDismiss = { viewModel.skipSyncSelection() },
            onSelectMode = { mode, height -> viewModel.onSyncModeSelected(mode, height) },
            // Help icons are intentionally hidden in the post-import flow:
            // re-opening the sheet from an EducationSheet stack is risky in
            // this constrained context, so we suppress the affordance entirely.
            onTopicHelp = {},
            showHelpIcons = false,
            tipBlockNumber = uiState.tipBlockNumber
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Recover Wallet") },
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
                Text("Paste from Clipboard")
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
                Text("Have a private key instead?")
            }

            // Import button
            Button(
                onClick = { viewModel.importMnemonic() },
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
                Text("Import Wallet")
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
        title = { Text("Import Private Key", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your 64-character private key (hex) to restore your wallet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it.trim() },
                    label = { Text("Private Key") },
                    placeholder = { Text("0x...") },
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
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
