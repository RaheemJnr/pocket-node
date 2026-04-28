package com.rjnr.pocketnode.ui.screens.dao

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthMethod
import com.rjnr.pocketnode.data.gateway.models.DaoAction
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.DaoOverview
import com.rjnr.pocketnode.data.gateway.models.DaoTab
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.ui.screens.dao.components.DaoDepositCard
import com.rjnr.pocketnode.ui.screens.dao.components.DepositBottomSheet
import com.rjnr.pocketnode.ui.theme.PendingAmber
import com.rjnr.pocketnode.ui.theme.TestnetOrange

private val DaoGreen = Color(0xFF1ED882)

@Composable
fun DaoScreen(
    viewModel: DaoViewModel,
    onNavigateToPinVerify: () -> Unit = {},
    daoPinVerified: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val availableBalance by viewModel.availableBalance.collectAsState()
    val networkType by viewModel.networkType.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDepositSheet by remember { mutableStateOf(false) }
    var withdrawTarget by remember { mutableStateOf<DaoDeposit?>(null) }
    var unlockTarget by remember { mutableStateOf<DaoDeposit?>(null) }
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // Initial-load fullscreen spinner (only on first ever load — subsequent
        // refreshes use the PullToRefresh indicator).
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // Whole content lives inside PullToRefreshBox so the user can refresh
        // from anywhere on the screen (overview card, tabs, list, or the empty
        // state) without needing to navigate away and back. Single LazyColumn
        // hosts every section as an `item { ... }` — same pattern as HomeScreen.
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding())
        ) {
            val deposits = when (uiState.selectedTab) {
                DaoTab.ACTIVE -> uiState.activeDeposits
                DaoTab.COMPLETED -> uiState.completedDeposits
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item("overview") {
                    DaoOverviewCard(
                        overview = uiState.overview,
                        availableBalance = availableBalance,
                        networkType = networkType,
                        onDepositClick = { showDepositSheet = true }
                    )
                }

                item("tabs") {
                    DaoTabRow(
                        selectedTab = uiState.selectedTab,
                        activeCount = uiState.overview.activeCount,
                        completedCount = uiState.overview.completedCount,
                        onTabSelected = viewModel::selectTab
                    )
                }

                // Pending action banner — only when an action is in flight.
                uiState.pendingAction?.let { action ->
                    item("pending-action") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = PendingAmber.copy(alpha = 0.1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = PendingAmber
                                )
                                Text(
                                    text = when (action) {
                                        is DaoAction.Depositing -> "Depositing ${formatCkb(action.amount)} CKB..."
                                        is DaoAction.Withdrawing -> "Withdrawing from DAO..."
                                        is DaoAction.Unlocking -> "Unlocking DAO deposit..."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PendingAmber
                                )
                            }
                        }
                    }
                }

                if (deposits.isEmpty() && uiState.selectedTab == DaoTab.ACTIVE) {
                    item("empty-active") {
                        // fillParentMaxHeight gives the empty-state vertical
                        // breathing room AND keeps the LazyColumn scrollable so
                        // pull-to-refresh still detects the gesture.
                        Box(
                            modifier = Modifier.fillParentMaxHeight(0.85f),
                            contentAlignment = Alignment.Center
                        ) {
                            DaoEmptyState(onDepositClick = { showDepositSheet = true })
                        }
                    }
                } else if (deposits.isEmpty()) {
                    item("empty-completed") {
                        Box(
                            modifier = Modifier.fillParentMaxHeight(0.6f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No completed deposits",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Fully unlocked deposits are consumed on-chain. Check your transaction history for past DAO activity.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                        }
                    }
                } else {
                    items(deposits, key = { "${it.outPoint.txHash}:${it.outPoint.index}" }) { deposit ->
                        DaoDepositCard(
                            deposit = deposit,
                            onWithdraw = { withdrawTarget = deposit },
                            onUnlock = { unlockTarget = deposit }
                        )
                    }
                }
            }
        }
    }

    // Snackbar for errors
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Handle PIN auth result
    LaunchedEffect(daoPinVerified) {
        if (daoPinVerified) {
            viewModel.executeDeposit()
        }
    }

    // Handle auth requirement (biometric prompt or PIN navigation)
    LaunchedEffect(uiState.requiresAuth, uiState.authMethod) {
        if (!uiState.requiresAuth) return@LaunchedEffect
        when (uiState.authMethod) {
            AuthMethod.BIOMETRIC -> {
                val activity = context as? FragmentActivity ?: run {
                    viewModel.cancelAuth()
                    return@LaunchedEffect
                }
                val executor = ContextCompat.getMainExecutor(context)
                val prompt = BiometricPrompt(
                    activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            viewModel.executeDeposit()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                viewModel.dismissAuthPrompt()
                                onNavigateToPinVerify()
                            } else {
                                viewModel.cancelAuth()
                            }
                        }
                    }
                )
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Authenticate to Deposit")
                    .setSubtitle("Verify your identity to deposit to Nervos DAO")
                    .setNegativeButtonText("Use PIN")
                    .build()
                prompt.authenticate(promptInfo)
            }
            AuthMethod.PIN -> {
                viewModel.dismissAuthPrompt()
                onNavigateToPinVerify()
            }
            null -> {}
        }
    }

    // Deposit bottom sheet
    if (showDepositSheet) {
        DepositBottomSheet(
            availableBalance = availableBalance,
            currentApc = uiState.overview.currentApc,
            onDeposit = { amount ->
                showDepositSheet = false
                viewModel.deposit(amount)
            },
            onDismiss = { showDepositSheet = false }
        )
    }

    // Withdraw confirmation
    withdrawTarget?.let { deposit ->
        AlertDialog(
            onDismissRequest = { withdrawTarget = null },
            title = { Text("Withdraw from DAO") },
            text = {
                Column {
                    Text("Deposit: ${formatCkb(deposit.capacity)} CKB")
                    Text("Earned: +${formatCkb(deposit.compensation)} CKB")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your funds will remain locked until the current 180-epoch cycle ends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.withdraw(deposit)
                    withdrawTarget = null
                }) {
                    Text("Withdraw")
                }
            },
            dismissButton = {
                TextButton(onClick = { withdrawTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Unlock confirmation
    unlockTarget?.let { deposit ->
        val totalReceived = deposit.capacity + deposit.compensation
        AlertDialog(
            onDismissRequest = { unlockTarget = null },
            title = { Text("Unlock DAO Deposit") },
            text = {
                Column {
                    Text("Deposit: ${formatCkb(deposit.capacity)} CKB")
                    Text("Compensation: +${formatCkb(deposit.compensation)} CKB")
                    Text("Total received: ${formatCkb(totalReceived)} CKB")
                    Text("Fee: ~0.001 CKB")
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.unlock(deposit)
                    unlockTarget = null
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { unlockTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DaoOverviewCard(
    overview: DaoOverview,
    availableBalance: Long,
    networkType: NetworkType,
    onDepositClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Nervos DAO",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = formatCkb(overview.totalLocked) + " CKB",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "+${formatCkb(overview.totalCompensation)} CKB",
                style = MaterialTheme.typography.bodyMedium,
                color = DaoGreen
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "APC ~${String.format("%.2f", overview.currentApc)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Available Balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${formatCkb(availableBalance)} CKB",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = if (networkType == NetworkType.TESTNET) "Testnet" else "Mainnet",
                style = MaterialTheme.typography.labelSmall,
                color = if (networkType == NetworkType.TESTNET) TestnetOrange
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDepositClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Deposit")
            }
        }
    }
}

@Composable
private fun DaoTabRow(
    selectedTab: DaoTab,
    activeCount: Int,
    completedCount: Int,
    onTabSelected: (DaoTab) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            val tabs = listOf(
                DaoTab.ACTIVE to "Active ($activeCount)",
                DaoTab.COMPLETED to "Completed ($completedCount)"
            )
            tabs.forEach { (tab, label) ->
                val isSelected = selectedTab == tab
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface
                            else Color.Transparent,
                    onClick = { onTabSelected(tab) }
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DaoEmptyState(onDepositClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Earn rewards with Nervos DAO",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Deposit CKB to earn ~2.5% annual compensation. 180-epoch lock cycles.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDepositClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Make First Deposit")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Not seeing expected deposits? Try resyncing from an earlier block in Settings.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

internal fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%,.2f", ckb)
}

internal fun formatCkbFull(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%,.8f", ckb)
}
