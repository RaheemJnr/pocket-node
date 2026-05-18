package com.rjnr.pocketnode.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowDownLeft
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Landmark
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Users
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.ui.theme.ErrorRed
import com.rjnr.pocketnode.ui.theme.PendingAmber
import com.rjnr.pocketnode.ui.theme.SuccessGreen
import com.rjnr.pocketnode.ui.util.uaTestTag
import com.rjnr.pocketnode.util.formatBlockTimestamp
import java.util.Locale

@Composable
fun WalletBalanceCard(
    balanceCkb: Double,
    fiatBalance: String?,
    address: String,
    peerCount: Int,
    isBalanceHidden: Boolean = false,
    onToggleVisibility: () -> Unit = {},
    onCopyAddress: () -> Unit,
    onPeersClick: () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.uaTestTag("home-balance-row")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Wallet Balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onToggleVisibility,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (isBalanceHidden) Lucide.EyeOff else Lucide.Eye,
                        contentDescription = if (isBalanceHidden) "Show balance" else "Hide balance",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isBalanceHidden) "••••••" else String.format(Locale.US, "%,.2f CKB", balanceCkb),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isBalanceHidden) "••••" else (fiatBalance ?: "≈ — USD"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onCopyAddress)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (address.length > 12) {
                                "${address.take(6)}...${address.takeLast(4)}"
                            } else address,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Lucide.Copy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // Tappable shortcut to NodeStatus — same affordance shape as
                // the address copy chip so the row reads as two parallel
                // actions (#131).
                Surface(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onPeersClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .uaTestTag("home-peers-shortcut"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Lucide.Users,
                            contentDescription = null,
                            tint = if (peerCount > 0) MaterialTheme.colorScheme.primary else PendingAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$peerCount peers connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ActionRow(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onStake: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ActionButton(
            icon = Lucide.Send,
            label = "SEND",
            variant = ActionVariant.Primary,
            modifier = Modifier.weight(1f),
            onAction = onSend
        )
        ActionButton(
            icon = Lucide.Download,
            label = "RECEIVE",
            variant = ActionVariant.Outline,
            modifier = Modifier.weight(1f),
            onAction = onReceive
        )
        ActionButton(
            icon = Lucide.Landmark,
            label = "STAKE",
            variant = ActionVariant.Outline,
            modifier = Modifier.weight(1f),
            onAction = onStake
        )
    }
}

enum class ActionVariant { Primary, Outline, Disabled }

@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    variant: ActionVariant,
    badge: String? = null,
    onAction: () -> Unit
) {
    Box(
        modifier = modifier.then(
            if (variant != ActionVariant.Disabled) Modifier.clickable { onAction() } else Modifier
        )
    ) {
        Surface(
            color = when (variant) {
                ActionVariant.Primary -> MaterialTheme.colorScheme.primary
                ActionVariant.Outline -> Color.Transparent
                ActionVariant.Disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            },
            shape = RoundedCornerShape(24.dp),
            border = if (variant == ActionVariant.Outline) BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary
            ) else null,
            modifier = Modifier
                .height(100.dp)
                .fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = when (variant) {
                        ActionVariant.Primary -> Color.Black
                        ActionVariant.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.8f
                        )

                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    label,
                    color = when (variant) {
                        ActionVariant.Primary -> Color.Black
                        ActionVariant.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.8f
                        )

                        else -> MaterialTheme.colorScheme.primary
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (badge != null) {
            Surface(
                color = Color(0xFF333333),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
            ) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun TransactionItems(
    transaction: TransactionRecord,
    onClick: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val isFailed = transaction.status == "FAILED"
    val isPending = !isFailed && transaction.isPending()
    val daoColor = MaterialTheme.colorScheme.primary

    val backgroundColor by animateColorAsState(
        targetValue = if (isPending) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "bgColor"
    )

    val (icon, iconBgColor, amountColor) = when {
        transaction.isDaoDeposit() -> Triple(
            Lucide.Landmark,
            daoColor.copy(alpha = 0.15f),
            daoColor
        )

        transaction.isDaoWithdraw() -> Triple(
            Lucide.Landmark,
            PendingAmber.copy(alpha = 0.15f),
            PendingAmber
        )

        transaction.isDaoUnlock() -> Triple(
            Lucide.ArrowDownLeft,
            SuccessGreen.copy(alpha = 0.15f),
            SuccessGreen
        )

        transaction.isIncoming() -> Triple(
            Lucide.ArrowDownLeft,
            SuccessGreen.copy(alpha = 0.15f),
            SuccessGreen
        )

        transaction.isOutgoing() -> Triple(
            Lucide.ArrowUpRight,
            ErrorRed.copy(alpha = 0.15f),
            ErrorRed
        )

        transaction.isSelfTransfer() -> Triple(
            Lucide.ArrowLeftRight,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.onSurface
        )

        else -> Triple(
            Lucide.CircleHelp,
            Color(0xFFF2994A).copy(alpha = 0.15f),
            Color(0xFFF2994A)
        )
    }
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clickable { onClick() }
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = amountColor
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = transaction.formattedAmount(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = transaction.shorterTxHash(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = formatBlockTimestamp(transaction.blockTimestampHex),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )


                    }

                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (transaction.isDaoTransaction()) {
                    val badgeLabel = when {
                        transaction.isDaoDeposit() -> "DAO Deposit"
                        transaction.isDaoWithdraw() -> "DAO Withdraw"
                        transaction.isDaoUnlock() -> "DAO Unlock"
                        else -> "DAO"
                    }
                    Surface(
                        color = daoColor.copy(alpha = 0.1f),
                        shape = CircleShape
                    ) {
                        Text(
                            badgeLabel,
                            color = daoColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                // Status chip — distinct visuals for Failed vs Pending vs
                // Confirmed. Failed is tappable; the parent renders the
                // confirm dialog and routes the retry through HomeViewModel.
                val (chipText, chipFg, chipBg) = when {
                    isFailed -> Triple("Failed", ErrorRed, ErrorRed.copy(alpha = 0.15f))
                    transaction.isConfirmed() -> Triple("Confirmed", SuccessGreen, SuccessGreen.copy(alpha = 0.15f))
                    else -> Triple("Pending", PendingAmber, PendingAmber.copy(alpha = 0.15f))
                }
                Surface(
                    color = chipBg,
                    shape = CircleShape,
                    modifier = if (isFailed && onRetry != null) {
                        Modifier.clickable { onRetry() }
                    } else {
                        Modifier
                    }
                ) {
                    Text(
                        chipText,
                        color = chipFg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun WalletBalanceCardPreview() {
    WalletBalanceCard(
        balanceCkb = 1234.56,
        fiatBalance = "$123,456.78",
        address = "ckb1234567890abcdef",
        peerCount = 4,
        onCopyAddress = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun ActionRowPreview() {
    ActionRow(
        onSend = {},
        onReceive = {},
        onStake = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun TransactionItemPreview() {
    TransactionItems(
        transaction = TransactionRecord(
            txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            blockNumber = "0x123456",
            blockHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
            timestamp = System.currentTimeMillis() - 3600000,
            balanceChange = "0x3b9aca00", // 10 CKB in shannons (0x3b9aca00 = 1000000000)
            direction = "in",
            fee = "0x2710",
            confirmations = 12,
            blockTimestampHex = "0x18c8d0a7a00"
        ),
        onClick = {}


    )
}