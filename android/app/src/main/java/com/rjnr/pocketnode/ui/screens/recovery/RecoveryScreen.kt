package com.rjnr.pocketnode.ui.screens.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rjnr.pocketnode.data.vault.VaultReadPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(onRecoveryComplete: () -> Unit, onContinueReadOnly: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val activity = LocalContext.current as FragmentActivity
    DisposableEffect(viewModel) { onDispose { viewModel.cancelOperation() } }
    LaunchedEffect(state.stage) { if (state.stage == RecoveryStage.SUCCESS) onRecoveryComplete() }
    Scaffold(topBar = { TopAppBar(title = { Text("Recover wallet access") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your app PIN opens the dashboard. Wallet secrets require the independent wallet passphrase or authenticated device access.")
            Text("Without a usable backup, recovery phrase or private key, the app cannot reconstruct lost keys. Keep affected wallets read-only; balances and sync remain available. Changing the Android PIN back cannot restore an erased key.")
            if (state.restoredWalletCount > 0) Text("${state.restoredWalletCount} wallet(s) restored and verified.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.outcomes.forEach { (id, message) -> Text("${state.missingWallets.firstOrNull { it.walletId == id }?.name ?: id}: $message") }
            if (state.stage == RecoveryStage.RESTORING) {
                CircularProgressIndicator()
                TextButton(onClick = viewModel::cancelOperation) { Text("Cancel") }
            } else state.missingWallets.forEach { wallet ->
                Text(wallet.name, style = MaterialTheme.typography.titleMedium)
                if (wallet.hasBackup) OutlinedButton(onClick = { viewModel.restoreBackup(activity, wallet.walletId) }) { Text("Authenticate old backup PIN") }
                if (wallet.cleanupPending) {
                    Text("Legacy cleanup is pending. Every retained backup must authenticate; copies made with different old PINs may remain unresolved. Recovery data stays preserved.")
                    if (!wallet.hasBackup && wallet.hasV2Archive) OutlinedButton(onClick = { viewModel.resumeCleanup(activity, wallet.walletId) }) { Text("Resume legacy cleanup") }
                }
                OutlinedButton(onClick = { viewModel.unlock(activity, wallet.walletId, VaultReadPreference.PASSPHRASE) }) { Text("Use wallet passphrase") }
                OutlinedButton(onClick = { viewModel.unlock(activity, wallet.walletId, VaultReadPreference.DEFAULT) }) { Text("Use device authentication") }
                if (!wallet.cleanupPending) {
                    OutlinedButton(onClick = { viewModel.recoverExternal(activity, wallet.walletId, false) }) { Text("Use recovery phrase") }
                    OutlinedButton(onClick = { viewModel.recoverExternal(activity, wallet.walletId, true) }) { Text("Use private key (spending only)") }
                }
            }
            TextButton(onClick = onContinueReadOnly, enabled = state.stage != RecoveryStage.RESTORING) { Text("Keep wallets read-only and continue") }
        }
    }
}
