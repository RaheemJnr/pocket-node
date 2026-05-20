package com.rjnr.pocketnode.ui.screens.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Dialog shown after a successful send to a previously-unsaved address
 * ([#197](https://github.com/RaheemJnr/pocket-node/issues/197)).
 *
 * The address is read-only — saving here means "this address I just
 * sent to". The name field is blank by default rather than guessing
 * from any transaction metadata (there is none, and a wrong guess is
 * worse than no guess for an address book entry).
 *
 * Dismissal is sticky per-address: once the user picks "Not now", the
 * prompt does not re-appear for the same recipient within the current
 * process lifetime. After a process restart the prompt can fire again
 * — that's a tolerable second chance rather than a permanent block.
 */
@Composable
fun SaveContactDialog(
    address: String,
    onSave: (name: String, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to contacts?") },
        text = {
            Column {
                Text(
                    text = "Add this recipient to your address book to send to them faster next time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Alice") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address") },
                    enabled = false,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), notes.trim().ifEmpty { null }) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
