package com.rjnr.pocketnode.ui.screens.receive

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.rjnr.pocketnode.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.ui.screens.home.HomeViewModel
import com.rjnr.pocketnode.ui.theme.PendingAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    hasMnemonicBackup: Boolean = true,
    hasPinOrBiometrics: Boolean = true,
    onNavigateToBackup: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    var showBackupWarning by remember { mutableStateOf(!hasMnemonicBackup || !hasPinOrBiometrics) }

    if (showBackupWarning) {
        AlertDialog(
            onDismissRequest = { showBackupWarning = false },
            title = { Text(stringResource(R.string.receive_protect_title)) },
            text = {
                Text(stringResource(R.string.receive_protect_body))
            },
            confirmButton = {
                Button(onClick = {
                    showBackupWarning = false
                    onNavigateToBackup()
                }) {
                    Text(stringResource(R.string.receive_protect_back_up))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupWarning = false }) {
                    Text(stringResource(R.string.receive_protect_dismiss))
                }
            }
        )
    }

    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentNetwotk = uiState.currentNetwork


    var expanded by remember { mutableStateOf(false) }

    val networkLabel = if (currentNetwotk == NetworkType.MAINNET) {
        "CKB Mainnet Address"
    } else {
        "CKB Testnet Address"
    }

    val displayAddress = when {
        uiState.address.isBlank() -> "Loading..."
        expanded -> uiState.address
        uiState.address.length > 16 ->
            "${uiState.address.take(10)}...${uiState.address.takeLast(6)}"
        else -> uiState.address
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding())
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Network label pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (currentNetwotk == NetworkType.MAINNET)
                            MaterialTheme.colorScheme.primary else PendingAmber
                        )
                )
                Text(
                    text = networkLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (currentNetwotk == NetworkType.MAINNET)
                        MaterialTheme.colorScheme.primary else PendingAmber
                )
            }

            // QR code card
            Card(
                modifier = Modifier
                    .size(240.dp + 48.dp) // 240dp QR + 24dp padding each side
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    QrCodeImage(
                        content = uiState.address,
                        modifier = Modifier.size(240.dp)
                    )
                }
            }

            // Address row — expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable(enabled = uiState.address.isNotBlank()) {
                        expanded = !expanded
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayAddress,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = if (expanded) TextOverflow.Visible else TextOverflow.Clip,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp
                    else Lucide.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Copy Address button (filled, primary green)
            Button(
                onClick = {
                    if (uiState.address.isNotBlank()) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm) // #304
                        clipboardManager.setText(AnnotatedString(uiState.address))
                        scope.launch {
                            snackbarHostState.showSnackbar("Address copied to clipboard")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    Lucide.Copy,
                    contentDescription = null,
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Copy Address",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Share button (outlined, primary green)
            OutlinedButton(
                onClick = {
                    if (uiState.address.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, uiState.address)
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Share CKB Address")
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Lucide.Share2, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Share",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Caption
            Text(
                text = "Share this address to receive CKB tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    var bitmap by remember(content) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(content) {
        if (content.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
                val w = matrix.width
                val h = matrix.height
                val pixels = IntArray(w * h) { i ->
                    if (matrix[i % w, i / w]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.setPixels(pixels, 0, w, 0, 0, w, h)
                }
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = stringResource(R.string.receive_qr_cd),
            modifier = modifier
        )
    }
}
