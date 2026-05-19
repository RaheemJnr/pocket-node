package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.update.DownloadState

/**
 * Telegram-style update banner that sits right above the bottom navigation.
 * Three visible states:
 *
 *   - [DownloadState.Downloading]: progress bar + "Downloading update… 23%"
 *   - [DownloadState.ReadyToInstall]: solid bar with "Tap to install" CTA
 *   - [DownloadState.Failed]: brief failure message with Retry / Dismiss
 *
 * Idle and Installing render nothing (Installing is short-lived; the system
 * package installer is foreground).
 */
@Composable
fun UpdateProgressBanner(
    state: DownloadState,
    onInstallClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DownloadState.Idle, DownloadState.Installing -> Unit
        is DownloadState.Downloading -> DownloadingRow(
            bytesDownloaded = state.bytesDownloaded,
            totalBytes = state.totalBytes,
            onCancelClick = onCancelClick,
            modifier = modifier,
        )
        DownloadState.ReadyToInstall -> ReadyRow(
            onInstallClick = onInstallClick,
            onDismissClick = onDismissClick,
            modifier = modifier,
        )
        is DownloadState.Failed -> FailedRow(
            reason = state.reason,
            onRetryClick = onRetryClick,
            onDismissClick = onDismissClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun DownloadingRow(
    bytesDownloaded: Long,
    totalBytes: Long,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction: Float? = if (totalBytes > 0L) {
        (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else null
    val pct = fraction?.let { (it * 100f).toInt() }

    // Compact Telegram-style row. Long-press to cancel — the X icon is
    // intentionally subtle so the banner stays scan-and-forget.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onCancelClick),
    ) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (pct != null) "Downloading update  $pct%" else "Downloading update…",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ReadyRow(
    onInstallClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onInstallClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Update ready  ·  Tap to install",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FailedRow(
    reason: String,
    onRetryClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onRetryClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Update download failed  ·  Tap to retry",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismissClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(
                "Dismiss",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
