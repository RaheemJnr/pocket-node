package com.rjnr.pocketnode.ui.screens.send

import android.content.Intent
import android.net.Uri
import com.rjnr.pocketnode.data.auth.AuthMethod
import com.rjnr.pocketnode.ui.screens.send.SendMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarColors
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanLine
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.resolveString
import com.composables.icons.lucide.X
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import com.rjnr.pocketnode.ui.theme.ErrorRed
import com.rjnr.pocketnode.ui.theme.PendingAmber
import com.rjnr.pocketnode.ui.theme.SuccessGreen
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    onNavigateBack: () -> Unit,
    onNavigateToScanner: () -> Unit = {},
    onNavigateToPinVerify: () -> Unit = {},
    scannedAddress: String? = null,
    sendAuthVerified: Boolean = false,
    prefillRecipient: String? = null,
    prefillAmountShannons: Long? = null,
    viewModel: SendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle scanned address
    LaunchedEffect(scannedAddress) {
        scannedAddress?.let { address ->
            if (address.isNotBlank()) {
                viewModel.updateRecipient(address)
            }
        }
    }

    // Retry-failed-tx prefill (#115). One-shot — keyed on the inputs so it
    // runs once per navigation. updateRecipient/updateAmount are the same
    // ViewModel hooks the rest of the screen uses.
    LaunchedEffect(prefillRecipient, prefillAmountShannons) {
        prefillRecipient?.let { viewModel.updateRecipient(it) }
        prefillAmountShannons?.let { shannons ->
            val ckb = shannons / 100_000_000.0
            // Locale.US — sanitizeAmount rejects comma decimals.
            val text = String.format(Locale.US, "%.8f", ckb).trimEnd('0').trimEnd('.')
            viewModel.updateAmount(text.ifEmpty { "0" })
        }
    }

    val context = LocalContext.current

    // Captured for the non-@Composable LaunchedEffect callback below.
    val sendAuthTitle = stringResource(R.string.send_auth_biometric_title)
    val sendAuthSubtitle = stringResource(R.string.send_auth_biometric_subtitle)
    val sendAuthNegative = stringResource(R.string.send_auth_biometric_negative)

    // Handle PIN auth result
    LaunchedEffect(sendAuthVerified) {
        if (sendAuthVerified) {
            viewModel.executeSend()
        }
    }

    // Handle auth requirement (biometric prompt or PIN navigation)
    LaunchedEffect(uiState.requiresAuth, uiState.authMethod) {
        if (!uiState.requiresAuth) return@LaunchedEffect
        when (uiState.authMethod) {
            AuthMethod.BIOMETRIC -> {
                val activity = context as? FragmentActivity ?: run {
                    viewModel.cancelAuth()
                    return@LaunchedEffect
                }
                val executor = ContextCompat.getMainExecutor(context)
                val prompt = BiometricPrompt(
                    activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            viewModel.executeSend()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                viewModel.cancelAuth()
                                onNavigateToPinVerify()
                            } else {
                                viewModel.cancelAuth()
                            }
                        }
                    }
                )
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(sendAuthTitle)
                    .setSubtitle(sendAuthSubtitle)
                    .setNegativeButtonText(sendAuthNegative)
                    .build()
                prompt.authenticate(promptInfo)
            }
            AuthMethod.PIN -> {
                viewModel.cancelAuth()
                onNavigateToPinVerify()
            }
            null -> {}
        }
    }

    // Show transaction status dialog when transaction is in progress
    if (uiState.txHash != null) {
        TransactionStatusDialog(
            txHash = uiState.txHash!!,
            transactionState = uiState.transactionState,
            statusMessage = uiState.statusMessage,
            confirmations = uiState.confirmations,
            networkType = uiState.networkType,
            onDismiss = {
                viewModel.clearTxHash()
                if (uiState.transactionState == TransactionState.CONFIRMED) {
                    onNavigateBack()
                }
            }
        )
    }

    // Show error dialog with better UX
    if (uiState.error != null && uiState.transactionState != TransactionState.SENDING) {
        ErrorDialog(
            errorMessage = uiState.error?.resolveString(context) ?: "An unknown error occurred",
            errorDetail = uiState.errorDetail,
            onDismiss = { viewModel.clearError() },
            onRetry = if (uiState.transactionState == TransactionState.FAILED) {
                { viewModel.clearError() }
            } else null
        )
    }

    var showContactPicker by remember { mutableStateOf(false) }
    val pickerSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showContactPicker) {
        ContactPickerSheet(
            sheetState = pickerSheetState,
            onDismiss = { showContactPicker = false },
            onContactPicked = { contact ->
                viewModel.selectContact(contact)
                showContactPicker = false
            },
            loadContacts = { q -> viewModel.searchContacts(q) },
        )
    }

    // #197: prompt to save a brand-new recipient after broadcast.
    uiState.saveContactPromptAddress?.let { addr ->
        SaveContactDialog(
            address = addr,
            onSave = { name, notes -> viewModel.saveContactPromptSubmit(name, notes) },
            onDismiss = { viewModel.saveContactPromptDismissed() },
        )
    }

    SendScreenUI(
        onNavigateBack = onNavigateBack,
        uiState = uiState,
        onNavigateToScanner = onNavigateToScanner,
        onOpenContactPicker = { showContactPicker = true },
        onSuggestionPicked = { viewModel.selectContact(it) },
        updateSendMode = viewModel::updateSendMode,
        updateRecipient = viewModel::updateRecipient,
        updateBulkRecipients = viewModel::updateBulkRecipients,
        updateAmount = viewModel::updateAmount,
        setMaxAmount = viewModel::setMaxAmount,
        sendTransaction = {
            // V2-aware send: pass the host FragmentActivity so the
            // ViewModel can drive a CryptoObject-bound BiometricPrompt
            // for wallets on kdfVersion=2 (#213). V1 wallets fall back to
            // the legacy non-CryptoObject biometric gate via the existing
            // requiresAuth state. Compose previews don't have a
            // FragmentActivity, so they keep using the no-arg overload.
            val activity = context as? FragmentActivity
            if (activity != null) {
                viewModel.sendTransaction(activity)
            } else {
                viewModel.sendTransaction()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreenUI(
    onNavigateBack: () -> Unit,
    uiState: SendUiState,
    onNavigateToScanner: () -> Unit,
    onOpenContactPicker: () -> Unit = {},
    onSuggestionPicked: (com.rjnr.pocketnode.data.database.entity.ContactEntity) -> Unit = {},
    updateSendMode: (SendMode) -> Unit,
    updateRecipient: (recipientAddress: String) -> Unit,
    updateBulkRecipients: (String) -> Unit,
    updateAmount: (amount: String) -> Unit,
    setMaxAmount: () -> Unit,
    sendTransaction: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Text(
                        "Send CKB",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onNavigateBack() }) {
                        Icon(
                            Lucide.ChevronLeft,
                            contentDescription = stringResource(R.string.common_back_cd),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        }
    )
    { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp),
            // verticalArrangement = Arrangement.spacedBy(16.dp)
        )
        {
            // Available Balance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            )
            {
                Text(
                    "Available",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    String.format(
                        Locale.US,
                        "%,.2f CKB",
                        uiState.availableBalance / 100_000_000.0
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { updateSendMode(SendMode.SINGLE) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.send_mode_single))
                }
                Button(
                    onClick = { updateSendMode(SendMode.BULK) },
                    modifier = Modifier.weight(1f),
                    colors = if (uiState.sendMode == SendMode.BULK) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(stringResource(R.string.send_mode_bulk))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            // Recipient Address
            Text(
                if (uiState.sendMode == SendMode.BULK) {
                    stringResource(R.string.send_bulk_recipients_label)
                } else {
                    "Recipient Address"
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.sendMode == SendMode.BULK) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    BasicTextField(
                        value = uiState.bulkRecipientsText,
                        onValueChange = { updateBulkRecipients(it) },
                        modifier = Modifier.fillMaxSize(),
                        maxLines = Int.MAX_VALUE,
                        singleLine = false,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
                        enabled = !uiState.isLoading,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        decorationBox = { innerTextField ->
                            if (uiState.bulkRecipientsText.isEmpty()) {
                                Text(
                                    stringResource(R.string.send_bulk_recipients_placeholder),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicTextField(
                        value = uiState.recipientAddress,
                        onValueChange = { updateRecipient(it) },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        singleLine = false,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
                        enabled = !uiState.isLoading,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        decorationBox = { innerTextField ->
                            if (uiState.recipientAddress.isEmpty()) {
                                Text(
                                    "Enter CKB Address",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    Icon(
                        imageVector = Lucide.BookUser,
                        contentDescription = stringResource(R.string.send_contact_picker_cd),
                        modifier = Modifier.clickable { onOpenContactPicker() }
                    )
                    Icon(
                        imageVector = Lucide.ScanLine,
                        contentDescription = stringResource(R.string.send_scan_cd),
                        modifier = Modifier.clickable{onNavigateToScanner()}
                    )
                }
            }

            // Autocomplete suggestions (#196). Hidden when the field has an
            // exact-match contact or when the user hasn't typed anything yet.
            if (uiState.sendMode == SendMode.SINGLE && uiState.recipientSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        ),
                ) {
                    uiState.recipientSuggestions.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionPicked(contact) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = contact.address.take(20) + "…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Saved-contact inline hint. Shown when the recipient address
            // exactly matches a contact — useful confirmation that the user
            // is sending to someone they know.
            if (uiState.sendMode == SendMode.SINGLE) uiState.matchedContact?.let { contact ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.send_saved_as, contact.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // "My Wallets" shortcut
            if (uiState.sendMode == SendMode.SINGLE && uiState.otherWallets.isNotEmpty()) {
                var showMyWallets by remember { mutableStateOf(false) }
                Box {
                    TextButton(
                        onClick = { showMyWallets = true },
                        enabled = !uiState.isLoading
                    ) {
                        Text(stringResource(R.string.send_my_wallets), style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = showMyWallets,
                        onDismissRequest = { showMyWallets = false }
                    ) {
                        uiState.otherWallets.forEach { wallet ->
                            val address = if (uiState.networkType == NetworkType.MAINNET)
                                wallet.mainnetAddress else wallet.testnetAddress
                            DropdownMenuItem(
                                text = { Text(wallet.name) },
                                // Guard against wallet rows that haven't populated the address
                                // for the current network yet (WalletEntity defaults to "").
                                enabled = !uiState.isLoading && address.isNotBlank(),
                                onClick = {
                                    updateRecipient(address)
                                    showMyWallets = false
                                }
                            )
                        }
                    }
                }
            }

            // Inline address validation indicator
            if (uiState.sendMode == SendMode.SINGLE) {
                AddressValidationIndicator(
                    address = uiState.recipientAddress,
                    currentNetwork = uiState.networkType
                )
            }

            if (uiState.sendMode == SendMode.BULK) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.send_bulk_summary,
                        uiState.bulkValidRecipients.size,
                        uiState.bulkDuplicateCount,
                        uiState.bulkInvalidEntries.size,
                        uiState.bulkBatchCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.bulkValidRecipients.isNotEmpty() && uiState.amountCkb.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.send_bulk_total_amount,
                            String.format(Locale.US, "%,.2f", uiState.bulkTotalAmountShannons / 100_000_000.0)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.bulkSubmittedTxHashes.isNotEmpty() || uiState.bulkFailureMessage != null || uiState.isLoading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (uiState.isLoading) {
                                    stringResource(
                                        R.string.send_bulk_progress,
                                        uiState.bulkCurrentBatch.coerceAtLeast(1),
                                        uiState.bulkBatchCount.coerceAtLeast(1),
                                        uiState.bulkCompletedRecipients
                                    )
                                } else {
                                    uiState.statusMessage
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            uiState.bulkFailureMessage?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            uiState.bulkSubmittedTxHashes.takeLast(3).forEach { hash ->
                                Text(
                                    text = hash,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            // Amount
            Text(
                "Amount",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = uiState.amountCkb,
                    onValueChange = { updateAmount(it) },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !uiState.isLoading,
                    decorationBox = { innerTextField ->
                        if (uiState.amountCkb.isEmpty()) {
                            Text(
                                "Enter Amount",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            )
                        }
                        innerTextField()
                    }
                )
                //Max
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = CircleShape,
                    onClick = { setMaxAmount() },
                    enabled = !uiState.isLoading && uiState.availableBalance > 0L && uiState.sendMode == SendMode.SINGLE,
                ) {
                    Text(
                        "MAX",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "CKB",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            }
            Text(
                "Min: 61 CKB · Max 8 decimal places",
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Burn Warning
            if (uiState.burnWarning != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Lucide.TriangleAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = uiState.burnWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Fee
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Estimated Fee",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
                val feeText = if (uiState.estimatedFee > 0) {
                    val feeCkb = uiState.estimatedFee / 100_000_000.0
                    "~${String.format(Locale.US, "%.6f", feeCkb)} CKB"
                } else {
                    "~0 CKB"
                }
                Text(
                    feeText,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Send button
            Button(
                onClick = { sendTransaction() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !uiState.isLoading &&
                        ((uiState.sendMode == SendMode.SINGLE && uiState.recipientAddress.isNotBlank()) ||
                            (uiState.sendMode == SendMode.BULK && uiState.bulkRecipientsText.isNotBlank())) &&
                        uiState.amountCkb.isNotBlank()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(uiState.statusMessage.ifEmpty { "Sending..." })
                } else {
                    Text(
                        if (uiState.sendMode == SendMode.BULK) {
                            stringResource(R.string.send_bulk_cta)
                        } else {
                            stringResource(R.string.send_send_ckb)
                        }
                    )
                }
            }
        }
    }
}

// Color constants from centralized theme
private val ColorInvalidRed = ErrorRed
private val ColorWarnAmber = PendingAmber

@Composable
fun AddressValidationIndicator(
    address: String,
    currentNetwork: NetworkType
) {
    if (address.isBlank()) return

    val (isValid, addressNetwork) = remember(address) {
        val valid = AddressUtils.isValid(address)
        Pair(valid, if (valid) AddressUtils.getNetwork(address) else null)
    }

    val (symbol, message, color) = when {
        !isValid -> Triple(Lucide.X, "Invalid address format", ColorInvalidRed)
        addressNetwork != null && addressNetwork != currentNetwork -> {
            val networkLabel = if (addressNetwork == NetworkType.TESTNET) "testnet" else "mainnet"
            Triple(
                Lucide.TriangleAlert,
                "This is a $networkLabel address on ${currentNetwork.name.lowercase()}",
                ColorWarnAmber
            )
        }

        else -> Triple(
            Lucide.Check,
            "Valid CKB ${currentNetwork.name.lowercase()} address",
            MaterialTheme.colorScheme.primary
        )
    }
    // Validation
    Row(
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = symbol,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            message,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ErrorDialog(
    errorMessage: String,
    errorDetail: String? = null,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Lucide.CircleAlert,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        title = {
            Text(
                text = "Transaction Failed",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Raw failure reason, compact. The full copyable version lives
                // in Node Status > App errors; this line lets a user relay the
                // gist without leaving the dialog (Alex, Telegram 2026-07).
                if (!errorDetail.isNullOrBlank() && errorDetail != errorMessage) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorDetail.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Full details: Settings › Node Status › App errors",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Show helpful tips for common errors
                if (errorMessage.contains("Insufficient", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Lucide.TriangleAlert,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Make sure you have enough CKB to cover the amount plus network fees.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                if (errorMessage.contains(
                        "Minimum",
                        ignoreCase = true
                    ) || errorMessage.contains("61", ignoreCase = true)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Lucide.TriangleAlert,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CKB requires a minimum of 61 CKB per transaction output.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.send_ok))
            }
        },
        dismissButton = if (onRetry != null) {
            {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.send_retry))
                }
            }
        } else null
    )
}

@Composable
fun TransactionStatusDialog(
    txHash: String,
    transactionState: TransactionState,
    statusMessage: String,
    confirmations: Int,
    networkType: NetworkType = NetworkType.MAINNET,
    onDismiss: () -> Unit
) {
    val isConfirmed = transactionState == TransactionState.CONFIRMED
    val isFailed = transactionState == TransactionState.FAILED

    AlertDialog(
        onDismissRequest = {
            // Only allow dismissing if confirmed or failed
            if (isConfirmed || isFailed) {
                onDismiss()
            }
        },
        icon = {
            when {
                isConfirmed -> {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(SuccessGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Lucide.CircleCheck,
                            contentDescription = stringResource(R.string.send_success_cd),
                            modifier = Modifier.size(40.dp),
                            tint = SuccessGreen
                        )
                    }
                }

                isFailed -> {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = stringResource(R.string.send_failed_cd),
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                else -> {
                    // Animated progress for pending states
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                }
            }
        },
        title = {
            Text(
                text = when (transactionState) {
                    TransactionState.CONFIRMED -> "Transaction Confirmed!"
                    TransactionState.FAILED -> "Transaction Failed"
                    TransactionState.SENDING -> "Sending..."
                    TransactionState.PENDING -> "Waiting for Confirmation"
                    TransactionState.PROPOSED -> "Processing..."
                    else -> "Transaction Submitted"
                },
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status message
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Progress steps (only show when not confirmed/failed)
                if (!isConfirmed && !isFailed) {
                    StatusSteps(currentState = transactionState)
                }

                // Transaction hash card
                val context = LocalContext.current
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Transaction Hash",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    val explorerBase = if (networkType == NetworkType.TESTNET) {
                                        "https://testnet.explorer.nervos.org/transaction/"
                                    } else {
                                        "https://explorer.nervos.org/transaction/"
                                    }
                                    com.rjnr.pocketnode.ui.util.openInBrowser(
                                        context, "$explorerBase$txHash",
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "View on Explorer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = txHash,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }

                // Confirmations badge (if confirmed)
                if (isConfirmed && confirmations > 0) {
                    Surface(
                        color = SuccessGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "✓ $confirmations confirmation${if (confirmations > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = SuccessGreen,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isConfirmed) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SuccessGreen
                    )
                ) {
                    Text(stringResource(R.string.send_done))
                }
            } else if (isFailed) {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.send_close))
                }
            }
        },
        dismissButton = {
            if (!isConfirmed && !isFailed) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text(stringResource(R.string.send_hide))
                }
            }
        }
    )
}

@Composable
fun StatusSteps(
    currentState: TransactionState
) {
    val steps = listOf(
        "Submitted" to (currentState != TransactionState.SENDING),
        "Pending" to (currentState == TransactionState.PENDING ||
                currentState == TransactionState.PROPOSED ||
                currentState == TransactionState.CONFIRMED),
        "Confirmed" to (currentState == TransactionState.CONFIRMED)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (label, isComplete) ->
            StatusStep(
                label = label,
                isComplete = isComplete,
                isCurrent = when (index) {
                    0 -> currentState == TransactionState.SENDING
                    1 -> currentState == TransactionState.PENDING || currentState == TransactionState.PROPOSED
                    2 -> currentState == TransactionState.CONFIRMED
                    else -> false
                }
            )

            if (index < steps.size - 1) {
                // Connector line
                val lineColor by animateColorAsState(
                    targetValue = if (steps[index + 1].second) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    label = "lineColor"
                )

                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .padding(horizontal = 4.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = lineColor
                    ) {}
                }
            }
        }
    }
}

@Composable
fun StatusStep(
    label: String,
    isComplete: Boolean,
    isCurrent: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(12.dp)
                .alpha(if (isCurrent) alpha else 1f),
            shape = MaterialTheme.shapes.small,
            color = when {
                isComplete -> MaterialTheme.colorScheme.primary
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        ) {}

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                isComplete -> MaterialTheme.colorScheme.primary
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SendScreenUIPreview() {
    CkbWalletTheme {
        SendScreenUI(
            onNavigateBack = {},
            uiState = SendUiState(
                availableBalance = 1245678900000L,
                recipientAddress = "ckb1qzda0cr08m85hc8j0ue0xlctre9270u8as09sh798atp99un0vscqqlm8nll72005vlj3sk05d3m",
                amountCkb = "100",
                networkType = NetworkType.MAINNET
            ),
            onNavigateToScanner = {},
            updateSendMode = {},
            updateRecipient = {},
            updateBulkRecipients = {},
            updateAmount = {},
            setMaxAmount = {},
            sendTransaction = {}
        )
    }
}
