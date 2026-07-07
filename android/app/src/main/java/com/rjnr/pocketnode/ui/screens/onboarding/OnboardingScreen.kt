package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rjnr.pocketnode.ui.util.resolveString
import com.rjnr.pocketnode.ui.util.uaTestTag

@Composable
fun OnboardingScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToImport: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // MainActivity extends FragmentActivity, so this cast is safe in production.
    // Required so OnboardingViewModel can drive the BiometricPrompt CryptoObject
    // flow for V2 keystore-bound writes (#289).
    val activity = androidx.compose.ui.platform.LocalContext.current
        as androidx.fragment.app.FragmentActivity

    // "Name your wallet" dialog state. Captured before the wallet is
    // actually created so the user-supplied name flows through into
    // WalletRepository.createWallet. The AddWallet flow has always
    // prompted for a name; this matches that surface so the two
    // wallet-creation entry points behave consistently (Telegram bug 3).
    var showNameDialog by remember { mutableStateOf(false) }
    var pendingWalletName by remember { mutableStateOf("My Wallet") }

    // No-device-lock advisory. When the device has neither biometric
    // enrolled nor a credential set, the V2 Keystore key chain can't
    // bind the wallet to user auth — the wallet still works, but the
    // keys are not hardware-protected until the user enrolls something.
    // Surface this as a warning the user can dismiss to continue, or
    // route them to Android Settings to enable a lock.
    var showNoDeviceLockWarning by remember { mutableStateOf(false) }
    // Captured at click time so the dialog's "Continue anyway" knows which
    // path to resume (Create wallet → name dialog vs Recover → import nav).
    var pendingNoLockAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // After wallet creation, navigate to backup screen (not Home)
    LaunchedEffect(uiState.isWalletCreated) {
        if (uiState.isWalletCreated) {
            onNavigateToBackup()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error.resolveString(context))
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon / Logo Placeholder
            Icon(
                imageVector = Lucide.Shield,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to Pocket Node",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Secure, private, and localized CKB management.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (uiState.wasCorrupted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "Your previous wallet data was corrupted during an app update and had to be reset. " +
                            "If you have a backup, please import it using your recovery phrase.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Options
            OnboardingOption(
                title = "Create New Wallet",
                description = "Generate a new wallet with a 12-word recovery phrase.",
                icon = Lucide.Plus,
                onClick = {
                    // Intercept the create flow when the device has no
                    // lock to give the user informed-consent before
                    // landing on the name dialog. They can still proceed.
                    if (uiState.noDeviceCredential) {
                        pendingNoLockAction = { showNameDialog = true }
                        showNoDeviceLockWarning = true
                    } else {
                        showNameDialog = true
                    }
                },
                isLoading = uiState.isLoading,
                modifier = Modifier.uaTestTag("onboarding-create-new")
            )

            Spacer(modifier = Modifier.height(16.dp))

            OnboardingOption(
                title = "Recover from Seed Phrase",
                description = "Import wallet using your 12-word recovery phrase.",
                icon = Lucide.KeyRound,
                onClick = {
                    // Same V2-Keystore informed-consent gate as Create New
                    // Wallet. The import path also writes via WalletKeyWriter
                    // and will pick the V1 software-only fallback when the
                    // device has no lock set.
                    if (uiState.noDeviceCredential) {
                        pendingNoLockAction = onNavigateToImport
                        showNoDeviceLockWarning = true
                    } else {
                        onNavigateToImport()
                    }
                },
                isLoading = uiState.isLoading,
                modifier = Modifier.uaTestTag("onboarding-recover")
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Footer
            Text(
                text = "Your keys, your crypto. Data stays on your device.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Name your wallet") },
            text = {
                Column {
                    Text(
                        text = "Give this wallet a label so you can spot it if you add more later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pendingWalletName,
                        onValueChange = { pendingWalletName = it },
                        singleLine = true,
                        label = { Text("Wallet name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    viewModel.createNewWallet(activity, pendingWalletName)
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showNoDeviceLockWarning) {
        AlertDialog(
            onDismissRequest = { showNoDeviceLockWarning = false },
            title = { Text("Continue without a device lock?") },
            text = {
                Text(
                    text = "This phone has no PIN, pattern, password, or biometric set.\n\n" +
                        "Your wallet will still work, but the keys will be stored in a software-only " +
                        "encryption layer that protects against casual theft of the device but is " +
                        "not hardware-bound to your fingerprint or PIN.\n\n" +
                        "If you enable a device lock later, the app will upgrade your wallet to " +
                        "hardware-backed protection automatically on the next launch.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNoDeviceLockWarning = false
                        pendingNoLockAction?.invoke()
                        pendingNoLockAction = null
                    },
                    // Upgrade-smoke harness clicks through this dialog: the CI
                    // emulator has no device lock, so it appears on every run.
                    modifier = Modifier.uaTestTag("onboarding-no-lock-continue")
                ) {
                    Text("Continue anyway")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showNoDeviceLockWarning = false
                        // ACTION_SECURITY_SETTINGS lands the user on the
                        // Security & privacy page on stock Android and on
                        // most OEM skins. Doesn't deep-link to the lock-
                        // screen flow but it's two taps from there.
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Text("Open Settings")
                    }
                    TextButton(onClick = {
                        showNoDeviceLockWarning = false
                        pendingNoLockAction = null
                    }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
private fun OnboardingOption(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        enabled = !isLoading,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = OutlinedCardTokens.ContainerShape
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Dummy object to satisfy M3 shape tokens if they aren't directly available in this compose version
private object OutlinedCardTokens {
    val ContainerShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
}
