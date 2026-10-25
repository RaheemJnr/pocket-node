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
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.resolveString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPinSetup: () -> Unit = {},
    onNavigateToPinVerify: () -> Unit = {},
    viewModel: SecuritySettingsViewModel = hiltViewModel(),
    onNavigateToBackup: (String) -> Unit = {},
    onNavigateToRecovery: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(ProtectionSection.OVERVIEW) }
    val scrollBehavior = key(section) { TopAppBarDefaults.exitUntilCollapsedScrollBehavior() }
    val navigateBack = { if (section != ProtectionSection.OVERVIEW) section = ProtectionSection.OVERVIEW else onNavigateBack() }
    BackHandler(enabled = section != ProtectionSection.OVERVIEW) { section = ProtectionSection.OVERVIEW }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var optimisticBiometric by remember { mutableStateOf<Boolean?>(null) }
    var optimisticAuthBeforeSend by remember { mutableStateOf<Boolean?>(null) }

    // Clear optimistic override when real state arrives
    LaunchedEffect(uiState.isBiometricEnabled) {
        optimisticBiometric = null
    }
    LaunchedEffect(uiState.isAuthBeforeSendEnabled) {
        optimisticAuthBeforeSend = null
    }

    DisposableEffect(viewModel) { onDispose { viewModel.cancelVaultOperation() } }
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
                    "Device authentication is optional convenience. If Android removes its key, verified vault wallets can still use their independent wallet passphrase. Legacy wallets may require their old backup PIN or external recovery secret. Keep an external backup for device loss or uninstall."
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
                        "Old PIN-encrypted backups remain on this device. Finish verified wallet protection and authenticated legacy cleanup before removing this dashboard PIN. New vault protection uses an independent strong wallet passphrase."
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Lucide.ArrowLeft, "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        key(section) { Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val activity = context as androidx.fragment.app.FragmentActivity
            WalletProtectionSettings(
                state = uiState,
                selectPreference = viewModel::selectUnlockPreference,
                change = { viewModel.changePassphrase(activity) },
                reset = { mode -> viewModel.resetPassphrase(activity, mode) },
                enroll = { id, mode -> viewModel.enrollDevice(activity, id, mode) },
                cancel = viewModel::cancelVaultOperation,
                section = section,
                navigate = { section = it },
                backup = onNavigateToBackup,
                recover = onNavigateToRecovery,
            )
            if (section == ProtectionSection.APP_LOCK) {
                Text("Control access to the app dashboard.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                // PIN Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "App PIN",
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
                                text = "No PIN set. Set a PIN to lock the dashboard on launch.",
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
                                    text = "Biometric app unlock",
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
                                    text = "Additional dashboard authentication. Vault signing always requires wallet authentication.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = optimisticAuthBeforeSend ?: uiState.isAuthBeforeSendEnabled,
                                onCheckedChange = { enabled ->
                                    // Per-operation auth toggle is itself a security state
                                    // change. Both enable AND disable route through
                                    // PinEntryScreen so a local attacker with a brief
                                    // unlocked-session window cannot turn the gate off
                                    // and immediately send (#292).
                                    optimisticAuthBeforeSend = enabled
                                    val action = if (enabled) PendingSecurityAction.ENABLE_AUTH_BEFORE_SEND
                                        else PendingSecurityAction.DISABLE_AUTH_BEFORE_SEND
                                    viewModel.setPendingAction(action)
                                    onNavigateToPinVerify()
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
        } }
    }
}
