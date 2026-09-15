package com.rjnr.pocketnode.ui.education

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.transaction.TransactionStatusUi
import com.rjnr.pocketnode.ui.transaction.BroadcastInfo
import com.rjnr.pocketnode.ui.transaction.TxDisplayState
import com.rjnr.pocketnode.ui.transaction.statusColors

/**
 * In-sheet explanation of what a transaction's current state means and what
 * happens next (#432).
 *
 * Lives beside [EducationSheet] so all of the wallet's explain-it copy stays in
 * one place and keeps one voice: plain language, no jargon, and a concrete
 * answer to "what do I do now" rather than a definition.
 *
 * Unlike [EducationSheet] this is inline rather than a sheet of its own: the
 * user is already looking at the transaction they are confused about, and a
 * second sheet stacked on the detail sheet would hide it.
 */
@Composable
fun TransactionStatusExplainer(
    state: TxDisplayState,
    broadcast: BroadcastInfo?,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors(state)
    val icon = when (state) {
        TxDisplayState.BROADCASTING -> Lucide.Send
        TxDisplayState.PENDING -> Lucide.Clock
        TxDisplayState.CONFIRMED -> Lucide.CircleCheck
        TxDisplayState.FAILED -> Lucide.CircleAlert
    }
    val titleRes = when (state) {
        TxDisplayState.BROADCASTING -> R.string.tx_explain_broadcasting_title
        TxDisplayState.PENDING -> R.string.tx_explain_pending_title
        TxDisplayState.CONFIRMED -> R.string.tx_explain_confirmed_title
        TxDisplayState.FAILED -> R.string.tx_explain_failed_title
    }
    // FAILED has no single body: the reason depends on how the watchdog gave up,
    // so it is assembled from the reason line plus the what-now line.
    val bodyLines = when (state) {
        TxDisplayState.BROADCASTING -> listOf(R.string.tx_explain_broadcasting_body)
        TxDisplayState.PENDING -> listOf(
            R.string.tx_explain_pending_body,
            R.string.tx_explain_pending_next,
        )
        TxDisplayState.CONFIRMED -> listOf(R.string.tx_explain_confirmed_body)
        TxDisplayState.FAILED -> listOf(
            TransactionStatusUi.failureReasonRes(broadcast),
            R.string.tx_explain_failed_next,
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.background),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.foreground,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground,
                )
            }
            bodyLines.forEach { line ->
                Text(
                    text = stringResource(line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
