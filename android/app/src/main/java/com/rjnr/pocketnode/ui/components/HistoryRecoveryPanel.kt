package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.history.RecoveryPresentation

/** Separate display-only hints. No transaction click, retry, send or DAO action callbacks. */
@Composable
fun HistoryRecoveryPanel(model: RecoveryPresentation) {
    var expanded by remember(model) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            Text(model.explanation, style = MaterialTheme.typography.bodySmall)
            if (model.isRevalidating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking wallet history", style = MaterialTheme.typography.labelSmall)
            }
            if (model.transactions.isNotEmpty() || model.daoHints.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide retained records" else "Show retained records")
                }
            }
            if (expanded) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.transactions.isNotEmpty()) {
                        Text(model.transactionHeading, style = MaterialTheme.typography.titleSmall)
                        model.transactions.forEach { row ->
                            Text(row.identity, style = MaterialTheme.typography.labelSmall)
                            Text(row.details, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                    if (model.daoHints.isNotEmpty()) {
                        Text(model.daoHeading, style = MaterialTheme.typography.titleSmall)
                        Text(model.daoExplanation, style = MaterialTheme.typography.bodySmall)
                        model.daoHints.forEach { row ->
                            Text(row.identity, style = MaterialTheme.typography.labelSmall)
                            Text(row.details, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
