package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.security.WalletBackupOperations
import com.rjnr.pocketnode.ui.security.ProtectionNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Only public progress crosses the native backup boundary. */
data class MnemonicBackupUiState(
    val isLoading: Boolean = false,
    val currentStep: Int = 1,
    val hasMnemonic: Boolean? = null,
    val error: String? = null,
)

@HiltViewModel
class MnemonicBackupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val operations: WalletBackupOperations,
) : ViewModel() {
    private val walletIdArg = savedStateHandle.get<String>("walletId")?.takeIf { it.isNotBlank() }
    private val _uiState = MutableStateFlow(MnemonicBackupUiState())
    val uiState = _uiState.asStateFlow()
    private var operation: Job? = null

    fun backup(activity: FragmentActivity) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val id = walletIdArg ?: walletRepository.getActive()?.walletId
                if (id == null) {
                    _uiState.update { it.copy(error = "Wallet unavailable") }
                    return@launch
                }
                when (val result = operations.show(activity, id, verify = true)) {
                    is WalletBackupOperations.Outcome.Shown -> _uiState.update {
                        it.copy(hasMnemonic = result.hasMnemonic, currentStep = if (result.backedUp) 3 else 1)
                    }
                    is WalletBackupOperations.Outcome.Failed -> _uiState.update { it.copy(error = result.message) }
                    WalletBackupOperations.Outcome.Cancelled -> Unit
                }
            } catch (e: CancellationException) { throw e }
            finally { _uiState.update { it.copy(isLoading = false) } }
        }
    }
    fun cancel() { operation?.cancel() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicBackupScreen(
    onNavigateToHome: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToPinVerify: () -> Unit = {},
    pinVerifiedFlow: SavedStateHandle? = null,
    simplified: Boolean = false,
    viewModel: MnemonicBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val activity = LocalContext.current as FragmentActivity
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.currentStep == 3) "Backup complete" else "Back up your wallet") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state.currentStep == 3) {
                Text("Keep your backup safe", style = MaterialTheme.typography.headlineSmall)
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (state.hasMnemonic) {
                            true -> "Your recovery phrase backup was verified. Keep it private and separate from your wallet passphrase."
                            false -> "Keep your private key safe. Saving a key backs up this account only; viewing it does not verify an external backup."
                            null -> "Keep your recovery material private and separate from your wallet passphrase."
                        },
                        modifier = Modifier.padding(20.dp),
                    )
                }
                Button(onClick = onNavigateToHome, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            } else {
                Text("Keep a way back in", style = MaterialTheme.typography.headlineSmall)
                Text("Create an external backup in case you lose access to this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BackupStep("1. Authenticate", "Authenticate to view your recovery material.")
                        HorizontalDivider()
                        BackupStep("2. Record your recovery material", if (state.hasMnemonic == false)
                            "Save your private key somewhere safe. Saving a key backs up this account only."
                        else "Write down your phrase or save your private key outside this app.")
                        HorizontalDivider()
                        BackupStep("3. Check your backup", when (state.hasMnemonic) {
                            true -> "Confirm three randomly selected words to verify your recovery phrase backup."
                            false -> "Keep a readable copy of your private key. Viewing it does not verify an external backup."
                            null -> "For a recovery phrase, confirm three randomly selected words."
                        })
                    }
                }
                ProtectionNotice("Keep it private", "Anyone with your recovery phrase or private key can access your funds.")
                state.error?.let { ProtectionNotice("Backup did not complete", it, error = true) }
                if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = { viewModel.backup(activity) },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.isLoading) "Working…" else "Authenticate and back up") }
                if (state.isLoading) TextButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun BackupStep(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
