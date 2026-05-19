package com.rjnr.pocketnode.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.update.UpdateInfo

/**
 * One-shot confirmation dialog telling the user a new version is available.
 * When the user taps Update Now, this dialog closes and download begins
 * silently in the background — the [com.rjnr.pocketnode.ui.components.UpdateProgressBanner]
 * sitting above the bottom navigation surfaces progress and the install CTA.
 */
@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
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

/**
 * Dialog shown when the user taps Update but has not granted the
 * "install from unknown sources" permission. Previously this state set a
 * flag in the ViewModel but no dialog was rendered for it, so tapping
 * Update silently did nothing.
 */
@Composable
fun InstallPermissionDialog(
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission needed") },
        text = {
            Text(
                "Android needs your permission to install updates for Pocket Node. " +
                    "Open settings, toggle \"Allow from this source\" on, then come back and tap Update again."
            )
        },
        confirmButton = {
            Button(onClick = onGrant) {
                Text("Open settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}
