package com.rjnr.pocketnode.ui.screens.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.ui.util.addressFor
import com.rjnr.pocketnode.ui.util.resolveString
import com.rjnr.pocketnode.ui.components.WalletAvatar
import com.rjnr.pocketnode.ui.util.SensitiveClipboard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPinVerify: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    viewModel: WalletSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // #524: the add-account dialog and its typed name survive the re-auth
    // lock and rotation.
    var showAddAccountDialog by rememberSaveable { mutableStateOf(false) }

    fun unlockSensitive() {
        (context as? androidx.fragment.app.FragmentActivity)?.let(viewModel::loadSensitiveData)
    }
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.lockSeedPhrase() } }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolveString(context))
            viewModel.clearError()
        }
    }

    // F1/F3: no device lock -> warn before persisting a sub-account at the V1
    // software fallback, matching onboarding.


    // Delete confirmation dialog
    if (uiState.showDeleteConfirm) {
        val warningText = buildString {
            if (uiState.hasDaoDeposits) {
                append("This wallet has ${uiState.daoDepositAmount} CKB locked in Nervos DAO. ")
                append("Deleting without a backup means losing these funds forever. ")
            }
            if (uiState.pendingTxCount > 0) {
                append("This wallet has ${uiState.pendingTxCount} pending transaction${if (uiState.pendingTxCount > 1) "s" else ""} that haven't confirmed yet. ")
            }
            if (uiState.hasDaoDeposits || uiState.pendingTxCount > 0) {
                append("\n\nAre you absolutely sure?")
            } else {
                append("This will permanently remove the wallet and its keys. This cannot be undone.")
            }
        }

        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text(if (uiState.hasDaoDeposits) "Warning: Delete Wallet?" else "Delete Wallet?") },
            text = {
                Text(warningText)
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.wallet_settings_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text(stringResource(R.string.wallet_settings_cancel)) }
            }
        )
    }

    // Add sub-account dialog
    if (showAddAccountDialog) {
        var accountName by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddAccountDialog = false },
            title = { Text(stringResource(R.string.wallet_settings_new_account_title)) },
            text = {
                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text(stringResource(R.string.add_wallet_account_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (accountName.isNotBlank()) {
                            // MainActivity extends FragmentActivity; the activity-less
                            // V1 fallback was removed in v1.7.2 (#289).
                            val activity = context as androidx.fragment.app.FragmentActivity
                            viewModel.addSubAccount(activity, accountName.trim())
                            showAddAccountDialog = false
                        }
                    },
                    enabled = accountName.isNotBlank()
                ) { Text(stringResource(R.string.wallet_settings_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddAccountDialog = false }) { Text(stringResource(R.string.wallet_settings_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.requestDelete() }) {
                        Text(
                            text = "Remove",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val wallet = uiState.wallet ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // -- Wallet avatar + editable name --
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                WalletAvatar(
                    name = wallet.name,
                    colorIndex = wallet.colorIndex,
                    size = 64.dp
                )
                Spacer(Modifier.height(12.dp))

                if (uiState.isEditing) {
                    OutlinedTextField(
                        value = uiState.editName,
                        onValueChange = { viewModel.updateEditName(it) },
                        label = { Text(stringResource(R.string.wallet_settings_wallet_name_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.cancelEditing() }) {
                            Text(stringResource(R.string.wallet_settings_cancel))
                        }
                        Button(
                            onClick = { viewModel.saveName() },
                            enabled = uiState.editName.isNotBlank()
                        ) {
                            Text(stringResource(R.string.wallet_settings_save))
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.startEditing() }
                    ) {
                        Text(
                            text = wallet.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Lucide.Pencil,
                            contentDescription = stringResource(R.string.wallet_settings_edit_name_cd),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // -- Info section --
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))

                SettingsRow(
                    label = "Source",
                    value = when {
                        wallet.parentWalletId != null -> "Sub-Account"
                        wallet.type == "mnemonic" -> "Seed Phrase"
                        wallet.type == "raw_key" -> "Imported Key"
                        else -> wallet.type
                    }
                )

                if (wallet.derivationPath != null) {
                    SettingsRow(
                        label = "Derivation Path",
                        value = wallet.derivationPath
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsActionRow(label = "Backup wallet", value = if (uiState.isBackedUp) "Completed" else "Pending", onClick = onNavigateToBackup)
                SettingsActionRow(label = "View recovery material", onClick = { unlockSensitive() })
                if (uiState.hasMnemonic == false) Text("This protected wallet has a private key but no recovery phrase. It cannot derive new accounts.")
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // -- Accounts section (mnemonic wallets only, not sub-accounts themselves) --
            if (viewModel.hasMnemonic()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Accounts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { showAddAccountDialog = true }) {
                            Icon(
                                Lucide.Plus,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.wallet_settings_add_account))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (uiState.subAccounts.isEmpty()) {
                        Text(
                            text = "No sub-accounts yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        uiState.subAccounts.forEach { subAccount ->
                            SubAccountRow(subAccount, uiState.network)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    value: String? = null,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubAccountRow(wallet: WalletEntity, network: NetworkType) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WalletAvatar(
            name = wallet.name,
            colorIndex = wallet.colorIndex,
            size = 36.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = wallet.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                // Encoded for the active network, not always mainnet (#489)
                text = wallet.addressFor(network),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
