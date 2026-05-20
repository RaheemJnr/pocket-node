package com.rjnr.pocketnode.ui.screens.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared form fields used by both [AddContactScreen] and
 * [EditContactScreen]. Address is editable only on add — edit screens
 * pass [addressEditable] = false to keep the row's identity stable
 * (the existing useCount/lastUsedAt history is pegged to the address).
 */
@Composable
fun ContactFormFields(
    name: String,
    address: String,
    notes: String,
    onNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    addressEditable: Boolean = true,
    addressTrailingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("CKB address") },
            placeholder = { Text("ckb1… or ckt1…") },
            singleLine = true,
            enabled = addressEditable,
            trailingIcon = addressTrailingIcon,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Notes (optional)") },
            minLines = 2,
            maxLines = 4,
        )
    }
}
