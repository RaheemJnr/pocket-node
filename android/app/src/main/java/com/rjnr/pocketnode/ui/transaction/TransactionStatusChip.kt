package com.rjnr.pocketnode.ui.transaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.theme.ErrorRed
import com.rjnr.pocketnode.ui.theme.PendingAmber
import com.rjnr.pocketnode.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

/** Foreground/background pair for a status chip, resolved against the theme. */
data class TxStatusColors(val foreground: Color, val background: Color)

@Composable
fun statusColors(state: TxDisplayState): TxStatusColors {
    val broadcasting = MaterialTheme.colorScheme.primary
    return when (state) {
        TxDisplayState.BROADCASTING -> TxStatusColors(broadcasting, broadcasting.copy(alpha = 0.15f))
        TxDisplayState.PENDING -> TxStatusColors(PendingAmber, PendingAmber.copy(alpha = 0.15f))
        TxDisplayState.CONFIRMED -> TxStatusColors(SuccessGreen, SuccessGreen.copy(alpha = 0.15f))
        TxDisplayState.FAILED -> TxStatusColors(ErrorRed, ErrorRed.copy(alpha = 0.15f))
    }
}

/**
 * Resolves the badge text for a state, appending the elapsed time while the
 * transaction is still in flight: "Pending · 2 min", "Broadcasting · <1 min".
 *
 * [nowMillis] is passed in (rather than read here) so the caller controls how
 * often the label re-renders — see [rememberTickingNow].
 */
@Composable
fun statusLabelText(
    state: TxDisplayState,
    sinceMillis: Long?,
    nowMillis: Long,
): String {
    val status = stringResource(TransactionStatusUi.statusLabelRes(state))
    if (!TransactionStatusUi.showsElapsed(state) || sinceMillis == null) return status
    return stringResource(R.string.tx_status_with_elapsed, status, elapsedText(sinceMillis, nowMillis))
}

/** Renders [TransactionStatusUi.formatElapsed] to text, e.g. "2 min", "3 hr". */
@Composable
fun elapsedText(sinceMillis: Long?, nowMillis: Long): String {
    if (sinceMillis == null) return "—"
    val elapsed = TransactionStatusUi.formatElapsed(nowMillis - sinceMillis)
    return elapsed.value?.let { stringResource(elapsed.res, it) } ?: stringResource(elapsed.res)
}

/**
 * Status badge shared by the home list, the activity list and both detail
 * sheets, so one state always looks the same wherever it appears (#432).
 *
 * BROADCASTING additionally carries a small spinner: it is the only state where
 * the app itself is mid-operation, and the issue's core complaint was that a
 * user cannot tell "we are still working" from "nothing is happening".
 */
@Composable
fun TransactionStatusChip(
    state: TxDisplayState,
    sinceMillis: Long?,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 6.dp,
) {
    val colors = statusColors(state)
    val label = statusLabelText(state, sinceMillis, nowMillis)
    Surface(
        color = colors.background,
        shape = RoundedCornerShape(8.dp),
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state == TxDisplayState.BROADCASTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.foreground,
                )
            }
            Text(
                text = label,
                style = textStyle,
                fontWeight = FontWeight.SemiBold,
                color = colors.foreground,
            )
        }
    }
}

/**
 * Wall clock that re-emits every [intervalMs] while [enabled], so "Pending ·
 * 2 min" ages on screen instead of freezing at the value it had when the list
 * was composed.
 *
 * This is a display ticker only: it reads the device clock and touches no
 * database, JNI or network. Sync and broadcast polling are untouched. It stops
 * as soon as nothing on screen is in flight.
 */
@Composable
fun rememberTickingNow(
    enabled: Boolean,
    intervalMs: Long = TICK_INTERVAL_MS,
): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled, intervalMs) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

/** 20s: fast enough that the minute boundary is never more than a third stale. */
const val TICK_INTERVAL_MS = 20_000L
