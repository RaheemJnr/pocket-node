package com.rjnr.pocketnode.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.rjnr.pocketnode.ui.util.uaTestTag
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.restorehint.RestoreHintCodec
import com.rjnr.pocketnode.data.restorehint.RestoreHintImporter
import com.rjnr.pocketnode.data.restorehint.RestoreHintPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** #559: user-facing copy for a rejected restore hint file. */
@StringRes
fun RestoreHintImporter.Reason.messageRes(): Int = when (this) {
    RestoreHintImporter.Reason.NOT_THIS_WALLET -> R.string.restore_hint_not_this_wallet
    RestoreHintImporter.Reason.OTHER_NETWORK -> R.string.restore_hint_other_network
    RestoreHintImporter.Reason.UNREADABLE -> R.string.restore_hint_unreadable
}

/**
 * A launcher for picking a restore hint file. [onPicked] receives the file's
 * text, or null when it could not be read; a cancelled picker calls nothing.
 */
@Composable
fun rememberRestoreHintPicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                onPicked(withContext(Dispatchers.IO) { readRestoreHint(context, uri) })
            }
        }
    }
    // Some providers label .json as octet-stream or plain text.
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
}

/** Reads at most one character past the codec's limit, so an oversized file is rejected without loading it all. */
private fun readRestoreHint(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(RestoreHintCodec.MAX_FILE_CHARS + 1)
        var total = 0
        while (total < buffer.size) {
            val n = reader.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        String(buffer, 0, total)
    }
}.getOrNull()

/** #559: confirms a verified hint before it changes the sync start. */
@Composable
fun RestoreHintReadyDialog(
    plan: RestoreHintPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_hint_ready_title)) },
        text = {
            Text(
                stringResource(
                    R.string.restore_hint_ready,
                    formatBlockHeight(plan.startBlock),
                    formatBlockHeight(plan.sourceCoverageStart),
                )
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.restore_hint_start_restore)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** A block height with the locale's digit grouping, e.g. 18,120,000. */
fun formatBlockHeight(height: Long): String = java.text.NumberFormat.getIntegerInstance().format(height)

/**
 * #559: "Use a restore hint file" on an import screen, or the picked file with
 * a Remove action. Shown before Import so the picker never opens while a
 * secret is held. [error] is a rejection to show under it.
 */
@Composable
fun RestoreHintFileRow(
    hasFile: Boolean,
    error: RestoreHintImporter.Reason?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hasFile) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.FileText, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.restore_hint_file_added),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) { Text(stringResource(R.string.restore_hint_remove)) }
            }
        } else {
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().uaTestTag("import-restore-hint")) {
                Icon(Lucide.FileText, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.restore_hint_use_file))
            }
            Text(
                stringResource(R.string.restore_hint_pick_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            Text(
                stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
