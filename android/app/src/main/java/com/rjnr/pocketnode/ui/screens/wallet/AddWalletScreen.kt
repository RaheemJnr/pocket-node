package com.rjnr.pocketnode.ui.screens.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Wallet
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.components.MnemonicWordInput
import com.rjnr.pocketnode.ui.util.resolveString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWalletScreen(
    onNavigateBack: () -> Unit = {},
    onWalletCreated: () -> Unit = {},
    onNewMnemonicWalletCreated: () -> Unit = {},
    viewModel: AddWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE: secret material (mnemonic / raw key) is entered or shown
    // on this screen — block screenshots, screen recording, and the recents
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
    // MainActivity extends FragmentActivity; required for V2 BiometricPrompt (#289).
    val activity = androidx.compose.ui.platform.LocalContext.current
        as androidx.fragment.app.FragmentActivity
    // 0 = menu, 1 = new wallet, 2 = import mnemonic, 3 = import key, 4 = sub-account
    // When the route carries a `parentId` query arg the user came in from
    // the WalletManager per-row "Add" button (Telegram bug 2). Skip the
    // mode picker and land directly on the sub-account form, with the
    // parent pre-selected by AddWalletViewModel.
    var selectedMode by remember {
        mutableIntStateOf(if (viewModel.preselectedParentId != null) 4 else 0)
    }

    LaunchedEffect(uiState.createdWallet) {
        if (uiState.createdWallet != null) {
            if (uiState.isNewlyGenerated) onNewMnemonicWalletCreated() else onWalletCreated()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolveString(context))
            viewModel.clearError()
        }
    }

    // F1/F3: no device lock -> warn before persisting at the V1 software
    // fallback, matching onboarding, instead of silently downgrading.
    if (uiState.showNoLockConsent) {
        com.rjnr.pocketnode.ui.components.NoDeviceLockConsentDialog(
            onConfirm = { viewModel.confirmNoLockConsent() },
            onDismiss = { viewModel.dismissNoLockConsent() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedMode == 0) "Add Wallet" else "New Wallet") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedMode == 0) onNavigateBack() else selectedMode = 0
                    }) {
                        Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding())
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            when (selectedMode) {
                0 -> {
                    OptionCard(
                        title = "New Wallet",
                        description = "Generate a new mnemonic seed phrase",
                        icon = { Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        onClick = { selectedMode = 1 }
                    )
                    OptionCard(
                        title = "Import Mnemonic",
                        description = "Restore from a 12/24-word seed phrase",
                        icon = { Icon(Lucide.Wallet, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        onClick = { selectedMode = 2 }
                    )
                    OptionCard(
                        title = "Import Private Key",
                        description = "Import a raw private key (hex)",
                        icon = { Icon(Lucide.Key, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        onClick = { selectedMode = 3 }
                    )
                    OptionCard(
                        title = "HD Sub-Account",
                        description = "Derive a new account from an existing wallet",
                        icon = { Icon(Lucide.Users, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        onClick = { selectedMode = 4 }
                    )
                }

                1 -> NewWalletForm(uiState, viewModel, activity)
                2 -> ImportMnemonicForm(uiState, viewModel, activity)
                3 -> ImportKeyForm(uiState, viewModel, activity)
                4 -> SubAccountForm(uiState, viewModel, activity)
            }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NewWalletForm(
    uiState: AddWalletUiState,
    viewModel: AddWalletViewModel,
    activity: androidx.fragment.app.FragmentActivity,
) {
    OutlinedTextField(
        value = uiState.name,
        onValueChange = { viewModel.updateName(it) },
        label = { Text(stringResource(R.string.add_wallet_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { viewModel.createNewWallet(activity) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isLoading
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.add_wallet_create))
        }
    }
}

@Composable
private fun ImportMnemonicForm(
    uiState: AddWalletUiState,
    viewModel: AddWalletViewModel,
    activity: androidx.fragment.app.FragmentActivity,
) {
    val clipboardManager = LocalClipboardManager.current

    OutlinedTextField(
        value = uiState.name,
        onValueChange = { viewModel.updateName(it) },
        label = { Text(stringResource(R.string.add_wallet_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(12.dp))

    OutlinedButton(
        onClick = {
            clipboardManager.getText()?.text?.let { viewModel.pasteImportMnemonic(it) }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Lucide.ClipboardPaste, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_wallet_paste))
    }
    Spacer(Modifier.height(12.dp))

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(12) { index ->
            MnemonicWordInput(
                index = index,
                value = uiState.importWords[index],
                suggestions = uiState.importSuggestions[index] ?: emptyList(),
                isError = uiState.importWordErrors.contains(index),
                onValueChange = { viewModel.updateImportWord(index, it) },
                onSuggestionSelected = { viewModel.selectImportSuggestion(index, it) }
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { viewModel.importMnemonic(activity) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isLoading && uiState.importWords.all { it.isNotBlank() }
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.add_wallet_import))
        }
    }
}

@Composable
private fun ImportKeyForm(
    uiState: AddWalletUiState,
    viewModel: AddWalletViewModel,
    activity: androidx.fragment.app.FragmentActivity,
) {
    OutlinedTextField(
        value = uiState.name,
        onValueChange = { viewModel.updateName(it) },
        label = { Text(stringResource(R.string.add_wallet_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = uiState.importPrivateKey,
        onValueChange = { viewModel.updateImportPrivateKey(it) },
        label = { Text(stringResource(R.string.add_wallet_private_key_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.add_wallet_private_key_placeholder)) }
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { viewModel.importRawKey(activity) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isLoading
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.add_wallet_import_key))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubAccountForm(
    uiState: AddWalletUiState,
    viewModel: AddWalletViewModel,
    activity: androidx.fragment.app.FragmentActivity,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    val selectedParent = uiState.parentWallets.firstOrNull { it.walletId == uiState.selectedParentId }

    OutlinedTextField(
        value = uiState.name,
        onValueChange = { viewModel.updateName(it) },
        label = { Text(stringResource(R.string.add_wallet_account_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(8.dp))
    ExposedDropdownMenuBox(
        expanded = dropdownExpanded,
        onExpandedChange = { dropdownExpanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedParent?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.add_wallet_parent_label)) },
            placeholder = { Text(stringResource(R.string.add_wallet_parent_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = dropdownExpanded,
            onDismissRequest = { dropdownExpanded = false }
        ) {
            if (uiState.parentWallets.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_wallet_no_parents)) },
                    onClick = { dropdownExpanded = false },
                    enabled = false
                )
            } else {
                uiState.parentWallets.forEach { wallet ->
                    DropdownMenuItem(
                        text = { Text(wallet.name) },
                        onClick = {
                            viewModel.selectParent(wallet.walletId)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { viewModel.createSubAccount(activity) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isLoading
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.add_wallet_create_sub_account))
        }
    }
}

