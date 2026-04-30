package com.rjnr.pocketnode.ui.screens.activity

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.composables.icons.lucide.*
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.ui.screens.home.HomeNavEvent
import com.rjnr.pocketnode.ui.theme.ErrorRed
import com.rjnr.pocketnode.ui.theme.PendingAmber
import com.rjnr.pocketnode.ui.theme.SuccessGreen
import com.rjnr.pocketnode.util.formatBlockTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val AmberPending = PendingAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onNavigateToSend: (recipient: String?, amountShannons: Long?) -> Unit = { _, _ -> },
    viewModel: ActivityViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    var retryDialogTx by remember { mutableStateOf<TransactionRecord?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var pendingCsvContent by remember { mutableStateOf<String?>(null) }

    // Collect one-shot nav events from the ViewModel (e.g. retry-failed-tx).
    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is HomeNavEvent.NavigateToSendWithPrefill -> {
                    onNavigateToSend(event.recipientAddress, event.amountShannons)
                }
            }
        }
    }

    // Retry-failed-tx confirmation. Copy mirrors HomeScreen exactly: FAILED is
    // a heuristic, not proof the network rejected the tx.
    retryDialogTx?.let { tx ->
        AlertDialog(
            onDismissRequest = { retryDialogTx = null },
            title = { Text("Retry transaction?") },
            text = { Text("This transaction may not have reached the network. Retry?") },
            confirmButton = {
                TextButton(onClick = {
                    retryDialogTx = null
                    viewModel.retryFailedTransaction(tx.txHash)
                }) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = { retryDialogTx = null }) { Text("Cancel") }
            }
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val csv = pendingCsvContent
        if (uri != null && csv != null) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                // UTF-8 BOM so Excel on Windows renders non-ASCII wallet/memo data correctly.
                stream.write("\uFEFF".toByteArray(Charsets.UTF_8))
                stream.write(csv.toByteArray(Charsets.UTF_8))
            }
        }
        // Always clear so a dismissed picker doesn't leave the previous CSV in memory.
        pendingCsvContent = null
    }

    val pagingItems = viewModel.transactionPagingFlow.collectAsLazyPagingItems()

    LaunchedEffect(Unit) {
        viewModel.exportEvent.collect { csv ->
            pendingCsvContent = csv
            exportLauncher.launch("transactions.csv")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Activity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.exportTransactions() }) {
                        Icon(
                            imageVector = Lucide.Download,
                            contentDescription = "Export CSV"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter tabs
            FilterTabRow(
                currentFilter = uiState.filter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            // Initial empty load → fullscreen spinner. Subsequent refreshes are
            // surfaced through the PullToRefreshBox indicator instead.
            val isInitialLoading = pagingItems.itemCount == 0 &&
                (pagingItems.loadState.refresh is LoadState.Loading || uiState.isLoading)

            PullToRefreshBox(
                isRefreshing = uiState.isLoading && pagingItems.itemCount > 0,
                onRefresh = { viewModel.refreshCache() },
                modifier = Modifier.fillMaxSize()
            ) {
            when {
                isInitialLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                pagingItems.loadState.refresh is LoadState.Error -> {
                    val errorMsg = (pagingItems.loadState.refresh as LoadState.Error).error.message
                    ErrorState(
                        message = uiState.error ?: errorMsg,
                        onRetry = { viewModel.refreshCache() }
                    )
                }

                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error,
                        onRetry = { viewModel.refreshCache() }
                    )
                }

                pagingItems.itemCount == 0 && pagingItems.loadState.refresh is LoadState.NotLoading -> {
                    EmptyState(filter = uiState.filter)
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(
                            count = pagingItems.itemCount,
                            key = { index -> pagingItems.peek(index)?.txHash ?: "item_$index" }
                        ) { index ->
                            val tx = pagingItems[index] ?: return@items

                            // Date header: show if first item or date changed from previous
                            val currentDate = tx.dateLabel()
                            val previousDate = if (index > 0) pagingItems.peek(index - 1)?.dateLabel() else null
                            if (currentDate != previousDate) {
                                DateGroupHeader(label = currentDate)
                            }

                            ActivityTransactionItem(
                                transaction = tx,
                                onClick = { selectedTransaction = tx },
                                onRetry = if (tx.status == "FAILED" && tx.isOutgoing()) {
                                    { retryDialogTx = tx }
                                } else null
                            )
                        }

                        // Loading indicator at bottom when appending more pages
                        if (pagingItems.loadState.append is LoadState.Loading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
            }

            selectedTransaction?.let { tx ->
                TransactionDetailSheet(
                    transaction = tx,
                    network = uiState.currentNetwork,
                    onDismiss = { selectedTransaction = null },
                    onCopyTxHash = { hash ->
                        clipboardManager.setText(AnnotatedString(hash))
                    },
                    onRetry = { failed ->
                        selectedTransaction = null
                        retryDialogTx = failed
                    },
                    onOpenExplorer = { url ->
                        com.rjnr.pocketnode.ui.util.openInBrowser(context, url)
                    }
                )
            }
        }
    }
}

// ─── Filter Tab Row ──────────────────────────────────────────────────────────

@Composable
private fun FilterTabRow(
    currentFilter: ActivityViewModel.Filter,
    onFilterSelected: (ActivityViewModel.Filter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActivityViewModel.Filter.entries.forEach { filter ->
            val label = when (filter) {
                ActivityViewModel.Filter.ALL -> "All"
                ActivityViewModel.Filter.RECEIVED -> "Received"
                ActivityViewModel.Filter.SENT -> "Sent"
            }
            FilterTab(
                label = label,
                selected = currentFilter == filter,
                onClick = { onFilterSelected(filter) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) primaryColor else onSurfaceVariantColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) primaryColor else Color.Transparent)
        )
    }
}

// ─── Date Group Header ────────────────────────────────────────────────────────

@Composable
private fun DateGroupHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

// ─── Transaction Row ──────────────────────────────────────────────────────────

@Composable
private fun ActivityTransactionItem(
    transaction: TransactionRecord,
    onClick: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val isFailed = transaction.status == "FAILED"
    val isPending = !isFailed && transaction.isPending()

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val (icon, iconBg, amountColor) = when {
        transaction.isDaoDeposit() -> Triple(Lucide.Landmark, primaryColor.copy(alpha = 0.15f), primaryColor)
        transaction.isDaoWithdraw() -> Triple(Lucide.Landmark, AmberPending.copy(alpha = 0.15f), AmberPending)
        transaction.isDaoUnlock() -> Triple(Lucide.ArrowDownLeft, SuccessGreen.copy(alpha = 0.15f), SuccessGreen)
        transaction.isIncoming() -> Triple(Lucide.ArrowDownLeft, primaryColor.copy(alpha = 0.15f), primaryColor)
        transaction.isSelfTransfer() -> Triple(Lucide.ArrowLeftRight, onSurfaceVariantColor.copy(alpha = 0.15f), onSurfaceVariantColor)
        else -> Triple(Lucide.ArrowUpRight, ErrorRed.copy(alpha = 0.15f), ErrorRed)
    }

    val typeLabel = when {
        transaction.isDaoDeposit() -> "Dao Deposit"
        transaction.isDaoWithdraw() -> "Dao Withdraw"
        transaction.isDaoUnlock() -> "Dao Unlock"
        transaction.isIncoming() -> "Received"
        transaction.isSelfTransfer() -> "Self Transfer"
        else -> "Sent"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = amountColor
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Middle: type label + tx hash + status badge
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (isFailed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = ErrorRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = if (onRetry != null) {
                            Modifier.clickable { onRetry() }
                        } else {
                            Modifier
                        }
                    ) {
                        Text(
                            text = "Failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = ErrorRed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (isPending) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = AmberPending.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberPending,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = transaction.shortTxHash(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Right: amount + time
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = transaction.formattedAmount(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = amountColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatBlockTimestamp(transaction.blockTimestampHex),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

// ─── Empty / Error States ─────────────────────────────────────────────────────

@Composable
private fun EmptyState(filter: ActivityViewModel.Filter) {
    val message = when (filter) {
        ActivityViewModel.Filter.ALL -> "No transactions yet"
        ActivityViewModel.Filter.RECEIVED -> "No received transactions"
        ActivityViewModel.Filter.SENT -> "No sent transactions"
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message ?: "Failed to load transactions",
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Retry", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── Transaction Detail Bottom Sheet ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    network: NetworkType,
    onDismiss: () -> Unit,
    onCopyTxHash: (String) -> Unit,
    onOpenExplorer: (String) -> Unit,
    onRetry: ((TransactionRecord) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val amountColor = when {
        transaction.isDaoDeposit() -> MaterialTheme.colorScheme.primary
        transaction.isDaoWithdraw() -> AmberPending
        transaction.isDaoUnlock() || transaction.isIncoming() -> MaterialTheme.colorScheme.primary
        transaction.isOutgoing() -> ErrorRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val explorerUrl = buildExplorerUrl(transaction.txHash, network)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = com.rjnr.pocketnode.ui.util.centredContentMaxWidth())
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            // Header row: title + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transaction Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(transaction = transaction)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Amount card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            transaction.isDaoDeposit() -> "Dao Deposit"
                            transaction.isDaoWithdraw() -> "Dao Withdraw"
                            transaction.isDaoUnlock() -> "Dao Unlock"
                            transaction.isIncoming() -> "Received"
                            transaction.isOutgoing() -> "Sent"
                            else -> "Amount"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transaction.formattedAmount(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = amountColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // TX Hash row with copy + explorer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TX Hash",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = transaction.shortTxHash(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { onCopyTxHash(transaction.txHash) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Lucide.Copy,
                        contentDescription = "Copy TX hash",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onOpenExplorer(explorerUrl) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Lucide.ExternalLink,
                        contentDescription = "View on explorer",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Block number
            DetailRow(
                label = "Block Number",
                value = formatBlockNumber(transaction.blockNumber)
            )

            // Block hash (hidden if "0x0")
            if (transaction.blockHash.isNotBlank() && transaction.blockHash != "0x0") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                DetailRow(
                    label = "Block Hash",
                    value = truncateHash(transaction.blockHash)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Time
            DetailRow(
                label = "Time",
                value = formatBlockTimestamp(transaction.blockTimestampHex)
            )

            // Fee (if non-zero)
            val feeShannons = transaction.fee.removePrefix("0x").toLongOrNull(16) ?: 0L
            if (feeShannons > 0L) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                val feeCkb = feeShannons / 100_000_000.0
                val feeFormatted = "%.8f".format(feeCkb).trimEnd('0').trimEnd('.')
                DetailRow(
                    label = "Fee",
                    value = "$feeFormatted CKB"
                )
            }

            // Retry CTA — only for FAILED plain transfers (see HomeScreen for why).
            if (transaction.status == "FAILED" && transaction.isOutgoing() && onRetry != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { onRetry(transaction) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Retry Transaction")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(transaction: TransactionRecord) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val isFailed = transaction.status == "FAILED"
    val (label, fg, bg) = when {
        isFailed -> Triple("Failed", errorColor, errorColor.copy(alpha = 0.15f))
        transaction.isConfirmed() -> Triple("Confirmed", primaryColor, primaryColor.copy(alpha = 0.15f))
        else -> Triple("Pending", AmberPending, AmberPending.copy(alpha = 0.15f))
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Returns a human-readable date group label for inline date headers in the paged list. */
private fun TransactionRecord.dateLabel(): String {
    val hex = blockTimestampHex
    if (hex.isNullOrBlank() || hex == "0x0" || hex == "0") return "Pending"
    val millis = runCatching {
        if (hex.startsWith("0x", ignoreCase = true))
            hex.removePrefix("0x").removePrefix("0X").toLong(16)
        else hex.toLong()
    }.getOrElse { 0L }
    if (millis <= 0L) return "Pending"
    val today = LocalDate.now(ZoneId.systemDefault())
    val date = Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "Earlier"
    }
}

private fun buildExplorerUrl(txHash: String, network: NetworkType): String {
    val base = when (network) {
        NetworkType.MAINNET -> "https://explorer.nervos.org/transaction"
        NetworkType.TESTNET -> "https://testnet.explorer.nervos.org/transaction"
    }
    return "$base/$txHash"
}

/** Formats a hex block number (e.g. "0x1170ea8") into a comma-separated decimal string. */
private fun formatBlockNumber(raw: String): String {
    val decimal = raw.removePrefix("0x").toLongOrNull(16) ?: return raw
    return "%,d".format(decimal)
}

/** Truncates a long hash to "0xabc...xyz" form for compact display. */
private fun truncateHash(hash: String): String {
    return if (hash.length > 20) "${hash.take(10)}...${hash.takeLast(6)}" else hash
}
