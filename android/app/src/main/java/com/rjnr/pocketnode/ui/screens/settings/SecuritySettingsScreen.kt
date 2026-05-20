package com.rjnr.pocketnode.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.resolveString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPinSetup: () -> Unit = {},
    onNavigateToPinVerify: () -> Unit = {},
    viewModel: SecuritySettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var optimisticBiometric by remember { mutableStateOf<Boolean?>(null) }

    // Clear optimistic override when real state arrives
    LaunchedEffect(uiState.isBiometricEnabled) {
        optimisticBiometric = null
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolveString(context))
            viewModel.clearError()
        }
    }

    // V2 Keystore key is invalidated whenever the user adds/removes a
    // biometric enrollment (setInvalidatedByBiometricEnrollment=true).
    // Surface this trade-off the first time the user touches the
    // biometric toggle while wallets exist on the device (#213 sub-PR 6).
    uiState.biometricEnrollmentWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissBiometricEnrollmentWarning() },
            title = { Text(if (warning.enabling) "Enable biometric unlock?" else "Disable biometric unlock?") },
            text = {
                Text(
                    "Pocket Node binds your wallet keys to your current biometrics. " +
                    "If you later add or remove a fingerprint or face, the keys on this device are wiped " +
                    "and the wallet can only be recovered from its recovery phrase.\n\n" +
                    "Make sure your recovery phrase is written down somewhere safe before you continue."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmBiometricEnrollmentWarning() }) {
                    Text(stringResource(R.string.security_settings_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissBiometricEnrollmentWarning() }) {
                    Text(stringResource(R.string.security_settings_cancel))
                }
            }
        )
    }

    if (showRemoveDialog) {
        if (!uiState.canRemovePin) {
            AlertDialog(
                onDismissRequest = { showRemoveDialog = false },
                title = { Text(stringResource(R.string.security_settings_pin_required_title)) },
                text = {
                    Text(
                        "A PIN is mandatory while you have a wallet. It protects your PIN-encrypted recovery backup.\n\n" +
                        "To remove the PIN, first delete every wallet in Wallet Manager. Make sure you have your recovery phrase written down before deleting."
                    )
                },
                confirmButton = {
                    Button(onClick = { showRemoveDialog = false }) { Text(stringResource(R.string.security_settings_pin_required_ok)) }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showRemoveDialog = false },
                title = { Text(stringResource(R.string.security_settings_remove_pin_title)) },
                text = {
                    Text(stringResource(R.string.security_settings_remove_pin_body))
                },
                confirmButton = {
                    Button(onClick = {
                        showRemoveDialog = false
                        viewModel.setPendingAction(PendingSecurityAction.REMOVE_PIN)
                        onNavigateToPinVerify()
                    }) {
                        Text(stringResource(R.string.security_settings_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoveDialog = false }) {
                        Text(stringResource(R.string.security_settings_cancel))
                    }
                }
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.ArrowLeft, "Back")
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
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // PIN Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "PIN Lock",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (uiState.hasPin) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Lucide.CircleCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(stringResource(R.string.security_settings_pin_enabled))
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row {
                            OutlinedButton(onClick = onNavigateToPinSetup) {
                                Text(stringResource(R.string.security_settings_change_pin))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { showRemoveDialog = true }) {
                                Text(
                                    "Remove PIN",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No PIN set. Set a PIN to lock your wallet on launch.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(onClick = onNavigateToPinSetup) {
                            Text(stringResource(R.string.security_settings_set_pin))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Biometric Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Biometric Unlock",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.biometricStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = optimisticBiometric ?: uiState.isBiometricEnabled,
                            onCheckedChange = { enabled ->
                                optimisticBiometric = enabled
                                val action = if (enabled) PendingSecurityAction.ENABLE_BIOMETRIC
                                    else PendingSecurityAction.DISABLE_BIOMETRIC
                                viewModel.setPendingAction(action)
                                onNavigateToPinVerify()
                            },
                            enabled = uiState.isBiometricAvailable && uiState.hasPin
                        )
                    }

                    if (!uiState.hasPin && uiState.isBiometricAvailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Set a PIN first to enable biometric unlock",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transaction Security Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Authenticate before sending",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Require biometrics or PIN before sending CKB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = uiState.isAuthBeforeSendEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.toggleAuthBeforeSend(enabled)
                            },
                            enabled = uiState.hasPin
                        )
                    }

                    if (!uiState.hasPin) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Set a PIN first to enable send authentication",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
