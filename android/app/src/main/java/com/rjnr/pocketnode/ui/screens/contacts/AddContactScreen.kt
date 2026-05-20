package com.rjnr.pocketnode.ui.screens.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.resolveString
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {},
    scannedAddress: String? = null,
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(scannedAddress) {
        scannedAddress?.takeIf { it.isNotBlank() }?.let { viewModel.onAddressChange(it) }
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onNavigateBack()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolveString(context))
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            ContactFormFields(
                name = uiState.name,
                address = uiState.address,
                notes = uiState.notes,
                onNameChange = viewModel::onNameChange,
                onAddressChange = viewModel::onAddressChange,
                onNotesChange = viewModel::onNotesChange,
                addressTrailingIcon = {
                    Box {
                        IconButton(onClick = onNavigateToScanner) {
                            Icon(Lucide.ScanLine, contentDescription = stringResource(R.string.add_contact_scan_cd))
                        }
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            // Paste-from-clipboard affordance — a separate row so the
            // address field's trailing icon stays the QR scan trigger
            // (paste is secondary).
            androidx.compose.material3.TextButton(
                onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isNotBlank()) viewModel.onAddressChange(text)
                },
            ) {
                Icon(
                    imageVector = Lucide.Clipboard,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.add_contact_paste))
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSaving && uiState.name.isNotBlank() && uiState.address.isNotBlank(),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.add_contact_save))
                }
            }
        }
    }
}
