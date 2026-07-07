package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.Bip39WordList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicWordInput(
    index: Int,
    value: String,
    suggestions: List<String>,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(suggestions, value) {
        expanded = suggestions.isNotEmpty() && !Bip39WordList.isValidWord(value)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.mnemonic_word_index_label, index + 1)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
            isError = isError,
            textStyle = MaterialTheme.typography.bodyMedium
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            suggestions.forEach { word ->
                DropdownMenuItem(
                    text = { Text(word) },
                    onClick = {
                        onSuggestionSelected(word)
                        expanded = false
                    }
                )
            }
        }
    }
}
