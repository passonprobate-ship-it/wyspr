package com.keystone.feature.onboarding.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.keystone.core.trust.HandshakeQr
import com.keystone.core.trust.HandshakeQrCodec
import java.util.concurrent.Executors

/**
 * Live camera QR scanner. PROTOCOLS.md §3.1 — payload is base32 CBOR.
 *
 * The screen handles its own runtime permission (CAMERA). On grant it
 * starts a CameraX preview + ImageAnalysis use case that ZXing decodes
 * frame-by-frame. Decoded payloads are pushed up via [onScanned] and
 * the analysis loop pauses to prevent duplicate emits.
 */
@Composable
fun ScanPeerQrScreen(
    onScanned: (HandshakeQr) -> Unit,
    onCancel: () -> Unit,
    onInvalid: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    // AtomicBoolean — set from the analyzer thread, read from the UI
    // thread. Using a Compose `var by remember` here race-fires the
    // onScanned callback because state mutation isn't visible to the
    // background analyzer immediately.
    val doneFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var done by remember { mutableStateOf(false) }
    val onScannedRef by rememberUpdatedState(onScanned)
    val onInvalidRef by rememberUpdatedState(onInvalid)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Scan your peer's QR", style = MaterialTheme.typography.titleLarge)
        Text(
            "Hold the camera over the peer's screen. Both devices must be running " +
                "Keystone and showing a fresh QR (refreshed within 5 minutes).",
            style = MaterialTheme.typography.bodyMedium,
        )

        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            if (hasCameraPermission && !done) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(
                        onPayload = { text ->
                            // compareAndSet returns true exactly once even
                            // under concurrent analyzer-thread access.
                            val qr = decodeOrNull(text)
                            if (qr == null) {
                                onInvalidRef("That QR is not a Keystone handshake code.")
                                return@CameraPreview
                            }
                            if (doneFlag.compareAndSet(false, true)) {
                                done = true
                                onScannedRef(qr)
                            }
                        },
                    )
                    ScanReticle()
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (hasCameraPermission) "Scanning…"
                        else "Camera permission is required to scan QR codes.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (!hasCameraPermission) {
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant camera access") }
        }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun CameraPreview(onPayload: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val onPayloadRef by rememberUpdatedState(onPayload)
    // Hold the camera provider so the DisposableEffect can unbind it
    // when the composable leaves — otherwise the camera keeps running
    // (and the analyzer keeps decoding) after the user navigates away.
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    val analysisRef = remember { java.util.concurrent.atomic.AtomicReference<ImageAnalysis?>(null) }

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
                    .build()
                analysisRef.set(analysis)
                analysis.setAnalyzer(executor, QrAnalyzer { text -> onPayloadRef(text) })
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private class QrAnalyzer(private val onPayload: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                data,
                image.width,
                image.height,
                0, 0,
                image.width,
                image.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
                ?: run { reader.reset(); null }
            result?.text?.let(onPayload)
        } finally {
            image.close()
        }
    }
}

@Composable
private fun ScanReticle() {
    Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        val cornerLen = size.minDimension * 0.15f
        val stroke = 4f
        val color = Color(0xFF80E0C0)
        // Four corner ticks — a non-photorealistic frame that doesn't
        // obscure the QR itself.
        // top-left
        drawCornerL(Offset(0f, 0f), cornerLen, stroke, color, horizontal = true, vertical = true)
        // top-right
        drawCornerL(Offset(size.width - cornerLen, 0f), cornerLen, stroke, color, horizontal = false, vertical = true)
        // bottom-left
        drawCornerL(Offset(0f, size.height - cornerLen), cornerLen, stroke, color, horizontal = true, vertical = false)
        // bottom-right
        drawCornerL(Offset(size.width - cornerLen, size.height - cornerLen), cornerLen, stroke, color, horizontal = false, vertical = false)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerL(
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

private fun decodeOrNull(payload: String): HandshakeQr? {
    return runCatching {
        val bytes = HandshakeQrCodec.fromBase32(payload.trim())
        HandshakeQrCodec.decode(bytes)
    }.getOrNull()
}
