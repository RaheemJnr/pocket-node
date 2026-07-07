package com.rjnr.pocketnode.ui.screens.scanner

import android.Manifest
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.rjnr.pocketnode.util.extractCkbAddress
import java.util.concurrent.Executors
import com.rjnr.pocketnode.util.redactAddress

private const val TAG = "QrScannerScreen"

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onScanResult: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.X, contentDescription = stringResource(R.string.qr_scanner_close_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                cameraPermissionState.status.isGranted -> {
                    CameraPreviewWithScanner(
                        onScanResult = { result ->
                            onScanResult(result)
                            onNavigateBack()
                        }
                    )
                }
                cameraPermissionState.status.shouldShowRationale -> {
                    PermissionRationale(
                        onRequestPermission = { cameraPermissionState.launchPermissionRequest() }
                    )
                }
                else -> {
                    PermissionDenied(onNavigateBack = onNavigateBack)
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(
    onScanResult: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var flashEnabled by remember { mutableStateOf(false) }
    var hasScanned by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val qrReader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (hasScanned) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                val decoded = decodeQrFromImage(imageProxy, qrReader)
                                imageProxy.close()

                                if (decoded != null) {
                                    val address = extractCkbAddress(decoded)
                                    if (address != null && !hasScanned) {
                                        hasScanned = true
                                        Log.d(TAG, "Scanned CKB address: ${address.redactAddress()}")
                                        // Dispatch the result callback to Main —
                                        // the caller invokes navController.popBackStack()
                                        // which mutates LifecycleRegistry state, and
                                        // setCurrentState must be called on the main
                                        // thread (#120 actual crash on Xiaomi 15 Pro:
                                        // IllegalStateException from pool-7-thread-1).
                                        mainExecutor.execute {
                                            haptic.performHapticFeedback(HapticFeedbackType.Confirm) // #304
                                            onScanResult(address)
                                        }
                                    }
                                }
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Scanning overlay
        ScannerOverlay()

        // Flash toggle button
        camera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                IconButton(
                    onClick = {
                        flashEnabled = !flashEnabled
                        cam.cameraControl.enableTorch(flashEnabled)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(50)
                        )
                ) {
                    Icon(
                        imageVector = if (flashEnabled) Lucide.Zap else Lucide.ZapOff,
                        contentDescription = if (flashEnabled) "Turn off flash" else "Turn on flash",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Decode a QR code from a CameraX [ImageProxy] using ZXing.
 * Returns the raw text or null if no QR code was found.
 */
private fun decodeQrFromImage(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    // Wrap the whole body — buffer/plane access and the
    // PlanarYUVLuminanceSource constructor can throw on devices where the
    // pixel format or stride doesn't match expectations (#120: Xiaomi 15 Pro
    // / Android 16 crashed scanning Joyid QR codes). The previous try only
    // covered `decodeWithState`; the unsafe parts ran outside.
    return try {
        if (imageProxy.planes.isEmpty()) return null
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val width = imageProxy.width
        val height = imageProxy.height
        // CameraX's Y plane is often padded — rowStride > width on many
        // devices (especially Xiaomi/Tecno/recent flagships). Pass the actual
        // stride as ZXing's `dataWidth` so it doesn't read past the buffer.
        val rowStride = plane.rowStride
        val dataWidth = if (rowStride >= width) rowStride else width
        if (bytes.size < dataWidth * height) {
            // Defensive: malformed buffer — skip this frame rather than OOB.
            return null
        }

        val source = PlanarYUVLuminanceSource(
            bytes, dataWidth, height, 0, 0, width, height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        reader.decodeWithState(bitmap).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

@Composable
private fun ScannerOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Semi-transparent background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )

        // Scanning frame
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp)
                    )
            )

            Text(
                text = "Point camera at a CKB address QR code",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PermissionRationale(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "To scan QR codes, this app needs access to your camera. " +
                   "Your camera will only be used for scanning addresses.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.qr_scanner_grant_permission))
        }
    }
}

@Composable
private fun PermissionDenied(
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera Permission Denied",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Camera permission is required to scan QR codes. " +
                   "Please enable it in your device settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNavigateBack) {
            Text(stringResource(R.string.qr_scanner_go_back))
        }
    }
}
