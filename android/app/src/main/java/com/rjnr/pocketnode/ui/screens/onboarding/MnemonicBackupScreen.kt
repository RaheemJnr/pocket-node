package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.compose.foundation.layout.*
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
    Scaffold(topBar = { TopAppBar(title = { Text(if (state.currentStep == 3) "Backup complete" else "Back up your wallet") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.currentStep == 3) {
                Text("Your recovery phrase backup was verified. Keep it private and separate from your wallet passphrase.")
                Button(onClick = onNavigateToHome) { Text("Continue") }
            } else {
                Text("Authenticate to view your recovery material in a protected window. A recovery phrase backup is completed only after checking three randomly selected words.")
                if (state.hasMnemonic == false) Text("This protected wallet contains a private key, not a recovery phrase. Saving a key backs up this account only.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { viewModel.backup(activity) }, enabled = !state.isLoading) { Text(if (state.isLoading) "Working…" else "Authenticate and back up") }
                TextButton(onClick = onNavigateBack) { Text("Back") }
            }
        }
    }
}
