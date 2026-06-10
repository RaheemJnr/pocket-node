package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.gateway.DaoConstants
import com.rjnr.pocketnode.util.sanitizeAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositBottomSheet(
    availableBalance: Long,
    currentApc: Double,
    onDeposit: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by remember { mutableStateOf("") }
    var reserveEnabled by remember { mutableStateOf(true) }
    var showRules by remember { mutableStateOf(false) }

    val amountCkb = amountText.toDoubleOrNull() ?: 0.0
    val amountShannons = (amountCkb * 100_000_000).toLong()
    val maxDepositable = if (reserveEnabled) {
        (availableBalance - DaoConstants.RESERVE_SHANNONS).coerceAtLeast(0L)
    } else {
        availableBalance
    }
    val isValid = amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS
        && amountShannons <= maxDepositable

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = com.rjnr.pocketnode.ui.util.centredContentMaxWidth())
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Deposit to Nervos DAO",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Available: ${formatCkb(maxDepositable)} CKB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Amount label
            Text(
                "Amount",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Amount input row (matches SendScreen pattern)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = amountText,
                    onValueChange = { newValue ->
                        val sanitized = sanitizeAmount(newValue)
                        if (sanitized != null) {
                            amountText = sanitized
                        }
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (amountText.isEmpty()) {
                            Text(
                                "Min 102 CKB",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            )
                        }
                        innerTextField()
                    }
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = CircleShape,
                    onClick = {
                        val maxCkb = maxDepositable / 100_000_000.0
                        // Locale.US — sanitizeAmount rejects comma decimals; on
                        // de/fr/es devices "%.8f".format(...) emits "0,12" and the
                        // MAX tap would silently no-op (#321).
                        amountText = String.format(java.util.Locale.US, "%.8f", maxCkb)
                            .trimEnd('0').trimEnd('.').ifEmpty { "0" }
                    },
                    enabled = maxDepositable >= DaoConstants.MIN_DEPOSIT_SHANNONS,
                ) {
                    Text(
                        "MAX",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "CKB",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            }

            // Helper + validation text
            Text(
                "Min: 102 CKB · Max 8 decimal places",
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
            if (amountText.isNotEmpty() && amountShannons < DaoConstants.MIN_DEPOSIT_SHANNONS) {
                Text(
                    "Minimum deposit is 102 CKB",
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            } else if (amountText.isNotEmpty() && amountShannons > maxDepositable) {
                Text(
                    "Exceeds available balance",
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = reserveEnabled,
                    onCheckedChange = { reserveEnabled = it }
                )
                Text(
                    text = "Reserve 62 CKB for future fees",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Reward estimates
            if (amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val reward30d = amountCkb * currentApc / 100 / 12
                        val reward360d = amountCkb * currentApc / 100
                        Text(
                            text = "Estimated rewards",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "30 days: ~${String.format("%.4f", reward30d)} CKB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "360 days: ~${String.format("%.4f", reward360d)} CKB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expandable rules
            TextButton(onClick = { showRules = !showRules }) {
                Text(if (showRules) "Hide Nervos DAO Rules" else "Nervos DAO Rules")
            }
            AnimatedVisibility(visible = showRules) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val rules = listOf(
                            "Minimum deposit: 102 CKB",
                            "Lock period: 180 epochs (~30 days)",
                            "You can withdraw anytime, but funds stay locked until the cycle ends",
                            "Unlock becomes available after the full lock cycle completes"
                        )
                        rules.forEach { rule ->
                            Text(
                                text = "\u2022 $rule",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.dao_sheet_cancel))
                }
                Button(
                    onClick = { onDeposit(amountShannons) },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.dao_sheet_deposit))
                }
            }
        }
    }
}

private fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
