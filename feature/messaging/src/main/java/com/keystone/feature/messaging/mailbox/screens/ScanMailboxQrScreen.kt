package com.keystone.feature.messaging.mailbox.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.keystone.feature.messaging.mailbox.MailboxClientViewModel
import com.keystone.feature.messaging.mailbox.MailboxQr
import com.keystone.feature.messaging.mailbox.MailboxQrCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera-based scanner for [MailboxQr] payloads. Decodes the QR,
 * hands the parsed cert back via [MailboxClientViewModel.applyScannedQr],
 * then navigates back.
 *
 * Structurally parallel to `feature:onboarding`'s `PeerQrCamera` —
 * the camera + analyzer plumbing is duplicated because moving it
 * into a shared module would require pulling CameraX into core:ui.
 * Acceptable for v1; mark for extraction when a third caller appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanMailboxQrScreen(
    onBack: () -> Unit,
    viewModel: MailboxClientViewModel = hiltViewModel(),
) {
    var errorText by remember { mutableStateOf<String?>(null) }
    // The CameraX analyzer dispatches onPayload from a background
    // executor thread. Touching navController.popBackStack() (or any
    // Compose nav APIs) from that thread silently no-ops — the user
    // sees the camera stay open even though the scan succeeded and
    // the binding got persisted. Hand the decoded QR off to a
    // mutableState; a LaunchedEffect on the composition's main
    // dispatcher then drives the apply + pop.
    var scannedQr by remember { mutableStateOf<MailboxQr?>(null) }

    LaunchedEffect(scannedQr) {
        val qr = scannedQr ?: return@LaunchedEffect
        Log.d("ScanMailboxQr", "main-thread apply: persisting + popping")
        viewModel.applyScannedQr(qr)
        onBack()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Scan mailbox QR", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Point the camera at the mailbox host's QR. Tap Back to cancel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                QrScanPane(
                    onScanned = { qr -> scannedQr = qr },
                    onInvalid = { msg -> errorText = msg },
                )
            }
            val err = errorText
            if (err != null) {
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QrScanPane(
    modifier: Modifier = Modifier,
    onScanned: (MailboxQr) -> Unit,
    onInvalid: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    val doneFlag = remember { AtomicBoolean(false) }
    val onScannedRef by rememberUpdatedState(onScanned)
    val onInvalidRef by rememberUpdatedState(onInvalid)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!hasPermission) {
            Text(
                "Tap to allow camera access",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            CameraPreview(onPayload = { text ->
                Log.d("ScanMailboxQr", "raw payload: len=${text.length} preview=\"${text.take(40)}…\"")
                val qr = decodeOrNull(text)
                if (qr == null) {
                    Log.w("ScanMailboxQr", "decode failed: not a valid MailboxQr (len=${text.length})")
                    onInvalidRef("That QR isn't a Keystone mailbox code.")
                    return@CameraPreview
                }
                Log.i("ScanMailboxQr", "scan ok: pub=${qr.mailboxPub.bytes.take(4)}… onion=${qr.onionAddress != null}")
                if (doneFlag.compareAndSet(false, true)) {
                    onScannedRef(qr)
                }
            })
            ScanReticle()
        }
    }
}

@Composable
private fun CameraPreview(onPayload: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val onPayloadRef by rememberUpdatedState(onPayload)
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val analysisRef = remember { AtomicReference<ImageAnalysis?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysisRef.get()?.clearAnalyzer() }
            runCatching { providerRef.get()?.unbindAll() }
            runCatching { executor.shutdown() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerRef.set(provider)
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolution)
                    .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                    .build()
                analysisRef.set(analysis)
                analysis.setAnalyzer(executor, QrAnalyzer { text -> onPayloadRef(text) })
                val camera = runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.getOrNull()
                runCatching {
                    val meteringPoint = previewView.meteringPointFactory.createPoint(
                        previewView.width / 2f,
                        previewView.height / 2f,
                    )
                    val focusAction = FocusMeteringAction.Builder(meteringPoint)
                        .disableAutoCancel()
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(focusAction)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private class QrAnalyzer(private val onPayload: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
                DecodeHintType.CHARACTER_SET to "ISO-8859-1",
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            if (plane.pixelStride != 1) return
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            val data = if (rowStride == width) {
                ByteArray(width * height).also { buffer.get(it, 0, width * height) }
            } else {
                val out = ByteArray(width * height)
                val rowBuf = ByteArray(rowStride)
                var dstPos = 0
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    val take = minOf(rowStride, buffer.remaining())
                    buffer.get(rowBuf, 0, take)
                    System.arraycopy(rowBuf, 0, out, dstPos, width)
                    dstPos += width
                }
                out
            }
            val source = PlanarYUVLuminanceSource(
                data, width, height,
                0, 0, width, height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.reset()
            val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
            result?.text?.let(onPayload)
        } catch (_: Throwable) {
            // never throw from the analyzer
        } finally {
            image.close()
        }
    }
}

@Composable
private fun ScanReticle() {
    Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val cornerLen = size.minDimension * 0.15f
        val stroke = 4f
        val color = Color(0xFF80E0C0)
        drawCornerL(Offset(0f, 0f), cornerLen, stroke, color, horizontal = true, vertical = true)
        drawCornerL(Offset(size.width - cornerLen, 0f), cornerLen, stroke, color, horizontal = false, vertical = true)
        drawCornerL(Offset(0f, size.height - cornerLen), cornerLen, stroke, color, horizontal = true, vertical = false)
        drawCornerL(Offset(size.width - cornerLen, size.height - cornerLen), cornerLen, stroke, color, horizontal = false, vertical = false)
    }
}

private fun DrawScope.drawCornerL(
    topLeft: Offset,
    length: Float,
    stroke: Float,
    color: Color,
    horizontal: Boolean,
    vertical: Boolean,
) {
    drawRect(
        color = color,
        topLeft = if (horizontal) topLeft else Offset(topLeft.x + length - stroke, topLeft.y),
        size = ComposeSize(stroke, length),
    )
    drawRect(
        color = color,
        topLeft = if (vertical) topLeft else Offset(topLeft.x, topLeft.y + length - stroke),
        size = ComposeSize(length, stroke),
    )
}

private fun decodeOrNull(payload: String): MailboxQr? =
    runCatching { MailboxQrCodec.fromBase32(payload.trim()) }.getOrNull()
