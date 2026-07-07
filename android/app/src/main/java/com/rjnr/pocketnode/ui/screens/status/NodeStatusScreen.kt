package com.rjnr.pocketnode.ui.screens.status

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rjnr.pocketnode.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniRemoteNode
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeStatusScreen(
    navController: NavController,
    viewModel: NodeStatusViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Status", "Logs")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.node_status_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Lucide.ChevronLeft, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> StatusTab(
                    uiState,
                    onCallRpc = { viewModel.callRpc(it) },
                    onClearErrors = { viewModel.clearErrorJournal() },
                )
                1 -> LogsTab(
                    logs = uiState.logs,
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }
}

@Composable
fun StatusTab(
    uiState: NodeStatusUiState,
    onCallRpc: (String) -> Unit,
    onClearErrors: () -> Unit = {},
) {
    var rpcMethod by remember { mutableStateOf("get_peers") }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val copyScope = androidx.compose.runtime.rememberCoroutineScope()
    val tipHeader = uiState.tipHeader
    val peers = uiState.peers

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App-error journal (Alex, Telegram 2026-07): release builds strip
        // logcat, so this is the ONE place field failures (rejected sends,
        // most importantly) are visible and copyable for a bug report.
        if (uiState.appErrors.isNotEmpty()) {
            item {
                StatusCard(
                    title = "App errors",
                    icon = Icons.Rounded.AccountTree,
                    trailing = "${uiState.appErrors.size}"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rowFmt = remember {
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        }
                        uiState.appErrors.take(5).forEach { e ->
                            Text(
                                text = "${rowFmt.format(java.util.Date(e.atMs))} [${e.tag}] ${e.message}",
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.TextButton(onClick = {
                                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                val text = uiState.appErrors.joinToString("\n") { e ->
                                    "[${fmt.format(java.util.Date(e.atMs))}] ${e.tag}: ${e.message}"
                                }
                                copyScope.launch {
                                    clipboard.setClipEntry(
                                        androidx.compose.ui.platform.ClipEntry(
                                            android.content.ClipData.newPlainText("App errors", text)
                                        )
                                    )
                                }
                            }) { Text("Copy all") }
                            androidx.compose.material3.TextButton(onClick = onClearErrors) { Text("Clear") }
                        }
                    }
                }
            }
        }

        // Tip Header card
        item {
            StatusCard(title = "Tip Header", icon = Icons.Rounded.AccountTree) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        InfoItem(
                            "Block Number",
                            tipHeader?.number ?: "--",
                            Modifier.weight(1f)
                        )
                        InfoItem(
                            "Epoch",
                            tipHeader?.epoch ?: "--",
                            Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LongInfoItem(
                        "Parent Hash",
                        tipHeader?.parentHash ?: "--"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LongInfoItem(
                        "Transactions Root",
                        tipHeader?.transactionsRoot ?: "--"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (tipHeader != null) formatTimestampAgo(tipHeader.timestamp) else "--",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        if (tipHeader != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Text(
                                    "Synced",
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 2.dp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Peers card
        item {
            StatusCard(
                title = "Peers",
                icon = Icons.Rounded.Group,
                trailing = "${peers.size} Connected"
            ) {
                if (peers.isEmpty()) {
                    Text(
                        "No peers connected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        peers.forEach { peer ->
                            PeerItem(
                                id = peer.nodeId,
                                version = peer.version,
                                duration = formatDuration(peer.connectedDuration)
                            )
                        }
                    }
                }
            }
        }

        // Database size card
        item {
            StatusCard(title = "Storage", icon = Icons.Rounded.Storage) {
                InfoItem("Database Size", formatBytes(uiState.dbSizeBytes))
            }
        }

        // Custom RPC section
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.node_status_custom_rpc), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = rpcMethod,
                    onValueChange = { rpcMethod = it },
                    label = { Text(stringResource(R.string.node_status_method_label)) },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onCallRpc(rpcMethod) }) {
                    Text(stringResource(R.string.node_status_call))
                }
            }
            if (uiState.rpcResult.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = uiState.rpcResult,
                        modifier = Modifier.padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LogsTab(logs: List<String>, onClearLogs: () -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    "No logs yet...",
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs) { line ->
                        val parsed = parseLogLine(line)
                        LogLine(
                            time = parsed.first,
                            message = parsed.second,
                            color = parsed.third
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { /* TODO: export logs */ },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.node_status_export_logs), fontSize = 14.sp)
            }
            IconButton(
                onClick = onClearLogs,
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// region UI Components

@Composable
fun StatusCard(
    title: String,
    icon: ImageVector,
    trailing: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (trailing != null) {
                    Text(
                        trailing,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun InfoItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun LongInfoItem(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                .padding(12.dp)
        )
    }
}

@Composable
fun PeerItem(id: String, version: String, duration: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "NODE ID",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    id,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "ACTIVE",
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Version",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Text(
                    version,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Duration",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Text(
                    duration,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun LogLine(time: String, message: String, color: Color) {
    Row {
        Text(
            time,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(8.dp))
        Text(message, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// endregion

// region Helpers

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatTimestampAgo(hexTimestamp: String): String {
    val ms = hexTimestamp.removePrefix("0x").toLongOrNull(16) ?: return "Unknown"
    val diffSeconds = (System.currentTimeMillis() - ms) / 1000
    return when {
        diffSeconds < 0 -> "Just now"
        diffSeconds < 60 -> "Updated ${diffSeconds}s ago"
        diffSeconds < 3600 -> "Updated ${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "Updated ${diffSeconds / 3600}h ago"
        else -> "Updated ${diffSeconds / 86400}d ago"
    }
}

private fun formatDuration(hexMs: String): String {
    val ms = hexMs.removePrefix("0x").toLongOrNull(16) ?: return hexMs
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private val logLevelRegex = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/(.*)$""")

private fun parseLogLine(line: String): Triple<String, String, Color> {
    val match = logLevelRegex.find(line)
    if (match != null) {
        val timestamp = "[${match.groupValues[1]}]"
        val level = match.groupValues[2]
        val message = match.groupValues[3]
        val color = when (level) {
            "V" -> Color(0xFF4ADE80)       // green
            "D" -> Color.LightGray
            "I" -> Color(0xFF60A5FA)       // blue
            "W" -> Color(0xFFFBBF24)       // amber
            "E", "F" -> Color(0xFFEF4444)  // red
            else -> Color.LightGray
        }
        return Triple(timestamp, message, color)
    }
    // Fallback: no parsing, show full line
    return Triple("", line, Color.LightGray)
}

// endregion

// region Previews

@Preview(showBackground = true)
@Composable
fun StatusTabPreview() {
    val sampleUiState = NodeStatusUiState(
        tipHeader = JniHeaderView(
            hash = "0xabc123",
            number = "0x11cbe5",
            epoch = "0x3a7015e00357e",
            timestamp = "0x${(System.currentTimeMillis() - 5000).toString(16)}",
            parentHash = "0xf661b7c4f4104c5e4d41cc86b6cba83c20f0139f6eb5e24d56ce04632dacacf6",
            transactionsRoot = "0x8fb290fc430323538e7b645c7323bca759d3dea436d5e7408985dea6a11545d1",
            proposalsHash = "0x0000000000000000000000000000000000000000000000000000000000000000",
            extraHash = "0x0000000000000000000000000000000000000000000000000000000000000000",
            dao = "0x00",
            nonce = "0x00"
        ),
        peers = listOf(
            JniRemoteNode("0.203.0", "QmNszGNQjYA6iP472bNnNE...", "0x36EE80"),
            JniRemoteNode("0.200.0", "QmZQK7KvwUcdVshRvmzP...", "0x1B7740")
        ),
        rpcResult = "",
    )
    CkbWalletTheme {
        StatusTab(uiState = sampleUiState, onCallRpc = {})
    }
}

@Preview(showBackground = true)
@Composable
fun LogsTabPreview() {
    val sampleLogs = listOf(
        "02-20 10:49:58.822 D/ckb-light-client(24474): tentacle::session started",
        "02-20 10:49:59.732 I/ckb-light-client(24474): ckb_light_client_lib sync block",
        "02-20 10:50:08.120 W/ckb-light-client(24474): Low peer count detected",
        "02-20 10:50:10.500 E/ckb-light-client(24474): Connection timeout"
    )
    CkbWalletTheme {
        LogsTab(logs = sampleLogs, onClearLogs = {})
    }
}

// endregion
