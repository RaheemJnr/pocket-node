package com.rjnr.pocketnode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.database.entity.WalletEntity

data class WalletGroup(
    val wallet: WalletEntity,
    val subAccounts: List<WalletEntity>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSelectorSheet(
    sheetState: SheetState,
    walletGroups: List<WalletGroup>,
    activeWalletId: String,
    onSelectAccount: (String) -> Unit,
    onManageWallets: () -> Unit,
    onDismiss: () -> Unit,
    balances: Map<String, String> = emptyMap()
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .widthIn(max = com.rjnr.pocketnode.ui.util.centredContentMaxWidth())
                .align(Alignment.CenterHorizontally)
        ) {
            // Title row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.account_selector_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = {
                    onDismiss()
                    onManageWallets()
                }) {
                    Text(stringResource(R.string.account_selector_manage))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn {
                walletGroups.forEach { group ->
                    item(key = "group_${group.wallet.walletId}") {
                        WalletGroupSection(
                            group = group,
                            activeWalletId = activeWalletId,
                            onSelectAccount = { walletId ->
                                onDismiss()
                                onSelectAccount(walletId)
                            },
                            balances = balances
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletGroupSection(
    group: WalletGroup,
    activeWalletId: String,
    onSelectAccount: (String) -> Unit,
    balances: Map<String, String> = emptyMap()
) {
    val hasSubAccounts = group.subAccounts.isNotEmpty()
    var isExpanded by remember { mutableStateOf(true) }

    Column {
        // Group header (collapsible if there are sub-accounts)
        if (hasSubAccounts) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WalletAvatar(
                    name = group.wallet.name,
                    colorIndex = group.wallet.colorIndex,
                    size = 24.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = group.wallet.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = stringResource(
                        if (isExpanded) R.string.account_selector_collapse_cd
                        else R.string.account_selector_expand_cd
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Parent wallet row (always visible)
        AccountRow(
            wallet = group.wallet,
            isActive = group.wallet.walletId == activeWalletId,
            onClick = { onSelectAccount(group.wallet.walletId) },
            cachedBalance = balances[group.wallet.walletId]
        )

        // Sub-account rows (collapsible)
        AnimatedVisibility(visible = isExpanded) {
            Column {
                group.subAccounts.forEach { subAccount ->
                    AccountRow(
                        wallet = subAccount,
                        isActive = subAccount.walletId == activeWalletId,
                        isSubAccount = true,
                        onClick = { onSelectAccount(subAccount.walletId) },
                        cachedBalance = balances[subAccount.walletId]
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun AccountRow(
    wallet: WalletEntity,
    isActive: Boolean,
    isSubAccount: Boolean = false,
    onClick: () -> Unit,
    cachedBalance: String? = null
) {
    val startPadding = if (isSubAccount) 40.dp else 16.dp
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isActive) 2.dp else 0.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(
                if (isActive) Modifier.border(borderWidth, borderColor, RoundedCornerShape(12.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WalletAvatar(
                name = wallet.name,
                colorIndex = wallet.colorIndex,
                size = 36.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wallet.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                // Show truncated address
                val address = wallet.mainnetAddress.ifEmpty { wallet.testnetAddress }
                if (address.isNotEmpty()) {
                    Text(
                        text = "${address.take(10)}...${address.takeLast(6)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (cachedBalance != null) {
                Text(
                    text = cachedBalance,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (isActive) {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = stringResource(R.string.account_selector_active_cd),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
