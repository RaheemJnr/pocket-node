package com.rjnr.pocketnode.ui.screens.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.components.WalletAvatar
import com.rjnr.pocketnode.ui.util.resolveString
import com.rjnr.pocketnode.ui.components.WalletGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletManagerScreen(
    onNavigateBack: () -> Unit = {},
    /**
     * Navigate to the Add Wallet screen. When [parentId] is non-null,
     * the destination jumps straight to the HD Sub-Account form with
     * that wallet pre-selected — used by the per-row "Add" button on
     * each parent wallet card.
     */
    onNavigateToAddWallet: (parentId: String?) -> Unit = {},
    onNavigateToWalletDetail: (String) -> Unit = {},
    viewModel: WalletManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolveString(context))
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToAddWallet(null) }) {
                Icon(Lucide.Plus, contentDescription = stringResource(R.string.wallet_manager_add_cd))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Discovery restore banner (#82 / #371): sub-account slots from
            // an imported seed whose scripts have on-chain history.
            if (uiState.foundCandidates.isNotEmpty()) {
                item {
                    val count = uiState.foundCandidates.size
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = if (count == 1) "Found 1 sub-account with history"
                                else "Found $count sub-accounts with history",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "This seed phrase has sub-accounts with on-chain activity. Restore them to see their balances and history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.Button(
                                onClick = {
                                    (context as? androidx.fragment.app.FragmentActivity)?.let {
                                        viewModel.restoreFoundSubAccounts(it)
                                    }
                                },
                                enabled = !uiState.isRestoring,
                            ) {
                                Text(if (uiState.isRestoring) "Restoring..." else "Restore")
                            }
                        }
                    }
                }
            }

            items(uiState.walletGroups, key = { it.wallet.walletId }) { group ->
                WalletGroupCard(
                    group = group,
                    // Previously: `viewModel.switchWallet(...)` — that
                    // silently switched to the wallet that owned the
                    // button (often already active), so the button
                    // appeared dead. Navigate to AddWallet with this
                    // parent pre-selected so the user lands on the
                    // sub-account form ready to name the new account.
                    // (Telegram bug 2)
                    onAddSubAccount = { onNavigateToAddWallet(group.wallet.walletId) },
                    onOpenSettings = { onNavigateToWalletDetail(group.wallet.walletId) }
                )
            }

            item { Spacer(Modifier.height(80.dp)) } // FAB clearance
        }
    }
}

@Composable
private fun WalletGroupCard(
    group: WalletGroup,
    onAddSubAccount: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val totalAccounts = 1 + group.subAccounts.size
    val accountLabel = if (totalAccounts == 1) "1 account" else "$totalAccounts accounts"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Parent wallet avatar
            WalletAvatar(
                name = group.wallet.name,
                colorIndex = group.wallet.colorIndex,
                size = 44.dp
            )

            Spacer(Modifier.width(12.dp))

            // Name + account count + sub-account dots
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.wallet.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = accountLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (group.subAccounts.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        SubAccountDots(group)
                    }
                }
            }

            // "Add" button — only for mnemonic (HD) wallets
            if (group.wallet.type == "mnemonic") {
                TextButton(onClick = onAddSubAccount) {
                    Text(stringResource(R.string.wallet_manager_add_short), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Chevron to wallet settings
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Lucide.ChevronRight,
                    contentDescription = stringResource(R.string.wallet_manager_settings_cd),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Renders up to 3 small overlapping avatar dots for sub-accounts. */
@Composable
private fun SubAccountDots(group: WalletGroup) {
    val visible = group.subAccounts.take(3)
    val dotSize = 18.dp
    val overlap = 6.dp

    Box {
        visible.forEachIndexed { index, subAccount ->
            Box(modifier = Modifier.offset(x = (index * (dotSize - overlap).value).dp)) {
                WalletAvatar(
                    name = subAccount.name,
                    colorIndex = subAccount.colorIndex,
                    size = dotSize
                )
            }
        }
        // Invisible spacer to size the Box correctly
        Spacer(
            Modifier.width(dotSize + (dotSize - overlap) * (visible.size - 1).coerceAtLeast(0))
        )
    }
}
