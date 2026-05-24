package com.wyspr.feature.onboarding.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.wyspr.core.trust.HandshakeQr
import com.wyspr.core.trust.HandshakeQrCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-contained QR scanner pane. Handles camera permission, lifecycle,
 * decoding, and one-shot delivery of the parsed [HandshakeQr] via
 * [onScanned]. Designed to live inside a larger screen (e.g. the
 * combined pair screen) rather than own the whole viewport.
 *
 * The camera always renders square — the host should constrain the
 * outer modifier to a square box. The reticle and analyzer expect the
 * preview view to be roughly that shape; otherwise off-centre QRs at
 * the edges of a wide preview won't decode reliably.
 *
 * If the user denies the camera permission, the pane renders a small
 * tap-to-grant button instead of the preview. The host doesn't need to
 * know about the permission state.
 */
@Composable
fun PeerQrCamera(
    modifier: Modifier = Modifier,
    onScanned: (HandshakeQr) -> Unit,
    onInvalid: (String) -> Unit = {},
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
    val doneFlag = remember { AtomicBoolean(false) }
    var done by remember { mutableStateOf(false) }
    val onScannedRef by rememberUpdatedState(onScanned)
    val onInvalidRef by rememberUpdatedState(onInvalid)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            !hasCameraPermission -> {
                Text(
                    "Tap to allow camera access",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(16.dp)
                        .clickable { launcher.launch(Manifest.permission.CAMERA) },
                )
            }
            done -> {
                Text(
                    "Scanned ✓",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                CameraPreview(
                    onPayload = { text ->
                        val qr = decodeOrNull(text)
                        if (qr == null) {
                            onInvalidRef("That QR isn't a Wyspr handshake code.")
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
                    // Match the display rotation so the YUV frames
                    // we get for analysis are oriented the same way
                    // as what the user sees in the preview. Without
                    // this, the analyzer gets sensor-natural frames
                    // (landscape on most phones) while the user holds
                    // the device portrait — ZXing's finder pattern
                    // detector is rotation-invariant but TRY_HARDER's
                    // segmentation paths choke on the mismatch.
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
                // Drive continuous autofocus — phone screens with a
                // displayed QR are small targets and Android's default
                // single-shot AF often locks on the user's hand or the
                // chrome around the QR rather than the QR itself.
                // FocusMeteringAction with AF+AE+AWB on a centred
                // metering point at 50% width with auto-cancel disabled
                // keeps the lens hunting for the right plane.
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

/**
 * ZXing analyzer that handles the two CameraX YUV gotchas that bite
 * QR decoding in practice:
 *
 *  1. **Row stride padding.** `ImageProxy.planes[0]` is the luminance
 *     plane, but the buffer's `rowStride` is often larger than the
 *     image width (e.g. 1280 width → 1280 stride on Pixel, but 1408
 *     stride on Samsung — rounded up to a hardware-friendly alignment).
 *     Passing the raw buffer to `PlanarYUVLuminanceSource` interprets
 *     those padding bytes as image data and produces a stretched,
 *     undecodable bitmap. We copy out exactly `width × height` bytes,
 *     skipping the per-row padding.
 *
 *  2. **Pixel stride.** For the Y plane, `pixelStride` is always 1
 *     per the YUV_420_888 spec, but we check defensively and bail to
 *     a no-op decode if the device lies.
 */
private class QrAnalyzer(private val onPayload: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // TRY_HARDER trades CPU for accuracy: enables alternate
                // decoder paths that handle slight blur, glare, off-axis
                // viewing, and partial luminance gradients — all typical
                // for phone-to-phone scanning.
                DecodeHintType.TRY_HARDER to true,
                // Mirror-image / inverted-luminance dispatch — some OEMs
                // return a Y plane that's effectively negated.
                DecodeHintType.ALSO_INVERTED to true,
                // Base32 payload is RFC 4648; declare the charset so
                // ZXing skips generic UTF-8 sniffing.
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
            // Reset reader state on every frame: decodeWithState can
            // accumulate partial-decode hints across frames that
            // confuse the next attempt under varying lighting.
            reader.reset()
            val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
            result?.text?.let(onPayload)
        } catch (_: Throwable) {
            // Analyzer must never throw — frame loss is preferable to
            // a process crash.
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

private fun decodeOrNull(payload: String): HandshakeQr? {
    return runCatching {
        val bytes = HandshakeQrCodec.fromBase32(payload.trim())
        HandshakeQrCodec.decode(bytes)
    }.getOrNull()
}
