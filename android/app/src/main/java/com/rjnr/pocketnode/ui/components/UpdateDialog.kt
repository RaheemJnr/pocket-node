package com.rjnr.pocketnode.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.update.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sizeText = if (updateInfo.fileSize > 0) {
        val sizeMb = updateInfo.fileSize / (1024.0 * 1024.0)
        stringResource(R.string.update_dialog_download_size, "%.1f".format(sizeMb))
    } else {
        ""
    }

    val versionLine = stringResource(R.string.update_dialog_version_available, updateInfo.latestVersion)
    val notesLine = if (updateInfo.releaseNotes.isNotBlank()) {
        "\n\n${updateInfo.releaseNotes.take(500)}"
    } else ""
    val messageText = versionLine + notesLine + sizeText

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = { Text(messageText) },
        confirmButton = {
            Button(onClick = onUpdate) {
                Text(stringResource(R.string.update_dialog_action_update_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_dialog_action_later))
            }
        }
    )
}
