package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rjnr.pocketnode.data.gateway.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.ui.screens.dao.formatCkb
import com.rjnr.pocketnode.ui.screens.dao.formatCkbFull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DaoGreen = Color(0xFF1ED882)

@Composable
fun DaoDepositCard(
    deposit: DaoDeposit,
    onWithdraw: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Full-precision amount
            Text(
                text = "${formatCkbFull(deposit.capacity)} CKB",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Compensation + APC row
            if (deposit.compensation > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "+${formatCkb(deposit.compensation)} CKB",
                        style = MaterialTheme.typography.bodySmall,
                        color = DaoGreen
                    )
                    if (deposit.apc > 0.0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ApcLabel(
                            apc = deposit.apc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar with triangle marker
            CompensationProgressBar(
                progress = deposit.compensationCycleProgress,
                phase = deposit.cyclePhase
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Cycle countdown text
            val cycleText = when (deposit.status) {
                DaoCellStatus.DEPOSITED -> {
                    val remainingProgress = 1f - deposit.compensationCycleProgress
                    val remainingDays = (remainingProgress * DaoConstants.WITHDRAW_EPOCHS * DaoConstants.HOURS_PER_EPOCH / 24).toInt()
                    if (remainingDays > 0) "The next compensation cycle starts in ~$remainingDays days"
                    else "Compensation cycle ending soon"
                }
                DaoCellStatus.LOCKED -> {
                    // "Unlockable in Xd Yh" was vague — didn't tell the user
                    // what was happening or what the next action is. New copy
                    // matches the verb on the action button ("Unlock") so the
                    // countdown reads as "when this button becomes tappable".
                    val hours = deposit.lockRemainingHours ?: 0
                    val days = hours / 24
                    val h = hours % 24
                    val timeStr = when {
                        days > 0 && h > 0 -> "${days}d ${h}h"
                        days > 0 -> "${days}d"
                        else -> "${h}h"
                    }
                    "Withdrawal in progress · Unlock in $timeStr"
                }
                DaoCellStatus.UNLOCKABLE -> "Ready to unlock!"
                DaoCellStatus.DEPOSITING, DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING ->
                    "Confirming..."
                else -> ""
            }
            if (cycleText.isNotEmpty()) {
                Text(
                    text = cycleText,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom row: date + action button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Deposit date with clock icon
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatStatusWithDate(deposit.status, deposit.depositTimestamp),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action button
                when (deposit.status) {
                    DaoCellStatus.DEPOSITED -> {
                        Button(
                            onClick = onWithdraw,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DaoGreen,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.dao_card_withdraw), fontWeight = FontWeight.Medium)
                        }
                    }
                    DaoCellStatus.UNLOCKABLE -> {
                        Button(
                            onClick = onUnlock,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DaoGreen,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.dao_card_unlock), fontWeight = FontWeight.Medium)
                        }
                    }
                    DaoCellStatus.DEPOSITING, DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    else -> { /* no action button */ }
                }
            }
        }
    }
}

private fun formatStatusWithDate(status: DaoCellStatus, timestampMs: Long): String {
    val prefix = when (status) {
        DaoCellStatus.DEPOSITED, DaoCellStatus.DEPOSITING -> "Deposited"
        DaoCellStatus.LOCKED, DaoCellStatus.UNLOCKABLE -> "Locked"
        DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING -> "Withdrawing"
        DaoCellStatus.COMPLETED -> "Completed"
    }
    if (timestampMs <= 0L) return prefix
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return "$prefix ${sdf.format(Date(timestampMs))}"
}
