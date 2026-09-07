package ovh.dep.pam.ui.scan

import android.Manifest
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import ovh.dep.pam.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ovh.dep.pam.crypto.KeyManager
import ovh.dep.pam.data.PairedDevice
import ovh.dep.pam.data.PairedDeviceRepository
import ovh.dep.pam.network.NsdDiscoveryManager
import ovh.dep.pam.network.QrPairingPayload
import ovh.dep.pam.network.TcpClient
import java.util.concurrent.Executors

/**
 * QR code scanning screen for device pairing.
 * Uses CameraX + MLKit to decode the pairing QR, then discovers the PC
 * via mDNS and completes the pairing handshake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onNavigateBack: () -> Unit,
    onPairingComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }

    var statusText by remember { mutableStateOf(context.getString(R.string.point_camera)) }
    var isProcessing by remember { mutableStateOf(false) }
    var scannedPayload by remember { mutableStateOf<QrPairingPayload?>(null) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var pairedServiceName by remember { mutableStateOf("") }

    var hasCameraPermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    val keyManager = remember { KeyManager(context) }
    val deviceRepo = remember { PairedDeviceRepository(context) }
    val nsdManager = remember { NsdDiscoveryManager(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val scanner = BarcodeScanning.getClient()
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        var found = false
                        for (barcode in barcodes) {
                            if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_UNKNOWN) {
                                val rawBytes = barcode.rawBytes ?: continue
                                handleQrCodeBytes(rawBytes, json) { payload ->
                                    found = true
                                    if (scannedPayload == null) {
                                        scannedPayload = payload
                                        isProcessing = true
                                        statusText = context.getString(R.string.qr_found, payload.serviceName)
                                        scope.launch {
                                            doPairing(payload, keyManager, deviceRepo, nsdManager, context, { statusText = it }, { pairedServiceName = it; showRevokeDialog = true }, { isProcessing = false; scannedPayload = null; statusText = it })
                                        }
                                    }
                                }
                                if (found) break
                            }
                        }
                        if (!found && !isProcessing) statusText = context.getString(R.string.invalid_file_format)
                    }
                    .addOnFailureListener { e ->
                        statusText = context.getString(R.string.error_prefix, e.message ?: "Unknown")
                    }
            } catch (e: Exception) {
                statusText = context.getString(R.string.error_prefix, e.message ?: "Unknown")
            }
        }
    }

    val clipboardManager = LocalClipboardManager.current


    // Stop NSD on leave
    DisposableEffect(Unit) {
        onDispose { nsdManager.stopDiscovery() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_qr_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasCameraPermission) {
                // Camera preview
                AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val executor = Executors.newSingleThreadExecutor()
                    val scanner = BarcodeScanning.getClient()

                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        @Suppress("DEPRECATION")
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !isProcessing) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage, imageProxy.imageInfo.rotationDegrees
                                )
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_UNKNOWN) {
                                                val rawBytes = barcode.rawBytes ?: continue
                                                handleQrCodeBytes(rawBytes, json) { payload ->
                                                    if (scannedPayload == null) {
                                                        scannedPayload = payload
                                                        isProcessing = true
                                                        statusText = context.getString(R.string.qr_found, payload.serviceName)

                                                        // Start pairing flow
                                                        scope.launch {
                                                            doPairing(
                                                                payload, keyManager, deviceRepo,
                                                                nsdManager, context,
                                                                onStatus = { statusText = it },
                                                                onComplete = { 
                                                                    pairedServiceName = it
                                                                    showRevokeDialog = true
                                                                },
                                                                onError = {
                                                                    isProcessing = false
                                                                    scannedPayload = null
                                                                    statusText = it
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis
                            )
                        } catch (e: Exception) {
                            Log.e("QrScan", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.camera_access_denied), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }

            // Status overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                }
                Icon(
                    Icons.Filled.QrCodeScanner, null,
                    modifier = Modifier.size(if (isProcessing) 24.dp else 48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_gallery))
                    }
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        onClick = { 
                            val text = clipboardManager.getText()?.text ?: ""
                            if (text.isNotBlank()) {
                                handleQrCodeText(text, json) { payload ->
                                    if (scannedPayload == null) {
                                        scannedPayload = payload
                                        isProcessing = true
                                        statusText = context.getString(R.string.qr_found, payload.serviceName)
                                        scope.launch {
                                            doPairing(payload, keyManager, deviceRepo, nsdManager, context, { statusText = it }, { pairedServiceName = it; showRevokeDialog = true }, { isProcessing = false; scannedPayload = null; statusText = it })
                                        }
                                    }
                                }
                            } else {
                                statusText = context.getString(R.string.invalid_file_format)
                            }
                        }
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.paste_clipboard))
                    }
                }
            }

            if (showRevokeDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(R.string.revoke_camera_title)) },
                    text = { Text(stringResource(R.string.revoke_camera_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                context.revokeSelfPermissionOnKill(Manifest.permission.CAMERA)
                                Toast.makeText(context, context.getString(R.string.revoke_kill_msg), Toast.LENGTH_LONG).show()
                            } else {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = android.net.Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            }
                            showRevokeDialog = false
                            onPairingComplete(pairedServiceName)
                        }) { Text(stringResource(R.string.revoke_now)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showRevokeDialog = false
                            onPairingComplete(pairedServiceName)
                        }) { Text(stringResource(R.string.later)) }
                    }
                )
            }
        }
    }
}

// ── QR decoding ────────────────────────────────────────────

private fun handleQrCodeBytes(bytes: ByteArray, json: Json, onPayload: (QrPairingPayload) -> Unit) {
    try {
        if (bytes.isNotEmpty() && bytes[0].toInt() in 1..bytes.size - 33) {
            val nameLen = bytes[0].toInt()
            val serviceName = String(bytes, 1, nameLen, Charsets.UTF_8)
            val pubKeyBytes = bytes.copyOfRange(1 + nameLen, 1 + nameLen + 32)
            val pubKeyBase64 = android.util.Base64.encodeToString(pubKeyBytes, android.util.Base64.NO_WRAP)
            onPayload(QrPairingPayload(serviceName, pubKeyBase64))
        } else {
            // fallback to parsing json directly if it was just json bytes
            val str = String(bytes, Charsets.UTF_8)
            val payload = json.decodeFromString<QrPairingPayload>(str)
            if (payload.serviceName.isNotBlank() && payload.pcPubKey.isNotBlank()) {
                onPayload(payload)
            }
        }
    } catch (e: Exception) {
        Log.w("QrScan", "Invalid QR data bytes: ${e.message}")
    }
}

private fun handleQrCodeText(text: String, json: Json, onPayload: (QrPairingPayload) -> Unit) {
    try {
        val decoded = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
        handleQrCodeBytes(decoded, json, onPayload)
    } catch (e: Exception) {
        Log.w("QrScan", "Invalid QR data text: ${e.message}")
    }
}

// ── Pairing flow ───────────────────────────────────────────

private suspend fun doPairing(
    payload: QrPairingPayload,
    keyManager: KeyManager,
    deviceRepo: PairedDeviceRepository,
    nsdManager: NsdDiscoveryManager,
    context: android.content.Context,
    onStatus: (String) -> Unit,
    onComplete: (String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        // Ensure we have a keypair
        if (!keyManager.hasKeyPair) {
            onStatus(context.getString(R.string.generating_keys))
            keyManager.generateKeyPair()
        }

        // Discover the service via mDNS
        onStatus(context.getString(R.string.searching_network, payload.serviceName))
        nsdManager.startDiscovery()

        // Wait for the service to appear (up to 30 seconds)
        var resolved: ovh.dep.pam.network.ResolvedService? = null
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 30_000) {
            resolved = nsdManager.services.value[payload.serviceName]
            if (resolved != null) break
            kotlinx.coroutines.delay(500)
        }

        if (resolved == null) {
            onError(context.getString(R.string.not_found_network, payload.serviceName))
            return
        }

        onStatus(context.getString(R.string.connecting_to, resolved.host, resolved.port.toString()))

        // TCP connect + handshake
        val tcpClient = TcpClient(keyManager)
        try {
            tcpClient.connect(resolved.host, resolved.port)
        } catch (e: Exception) {
            onError("${context.getString(R.string.failed_connect)} (${resolved.host}:${resolved.port} - ${e.message})")
            return
        }

        onStatus(context.getString(R.string.pairing))

        // Send pair request
        val deviceName = android.os.Build.MODEL
        val success = tcpClient.sendPairRequest(payload.pcPubKey, deviceName)
        tcpClient.disconnect()

        if (success) {
            // Save paired device
            deviceRepo.addDevice(
                PairedDevice(
                    serviceName = payload.serviceName,
                    pcPubKey = payload.pcPubKey,
                    deviceName = payload.serviceName
                )
            )
            onComplete(payload.serviceName)
        } else {
            onError(context.getString(R.string.pairing_rejected))
        }
    } catch (e: Exception) {
        Log.e("QrScan", "Pairing failed", e)
        onError(context.getString(R.string.error_prefix, e.message ?: "Unknown"))
    }
}
