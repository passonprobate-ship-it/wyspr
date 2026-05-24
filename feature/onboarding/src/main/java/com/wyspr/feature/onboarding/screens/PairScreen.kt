package com.wyspr.feature.onboarding.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyspr.core.identity.Identity
import com.wyspr.core.trust.HandshakeQr
import com.wyspr.core.trust.HandshakeQrCodec
import com.wyspr.core.ui.QrRenderer

/**
 * Combined pair-with-peer screen.
 *
 * Initial state (peerQr == null): the user's own QR sits above a live
 * camera scanner so each device can scan the other without screen
 * swapping. Replaces the previous DisplayQr → ScanPeerQr two-screen
 * flow. The camera permission is requested up-front by [PeerQrCamera]
 * so the Inviter doesn't fail on first scan attempt.
 *
 * After scanning (peerQr != null): the camera is torn down and the
 * QR remains visible so the peer can complete their own scan. A
 * Continue button gates the transition to fingerprint comparison —
 * advancing earlier would tear our QR off the screen before the peer
 * can scan it, and the Noise prologue requires both sides to hold
 * both QRs.
 *
 * Sidetrip CTAs (share APK, peer update) are tucked into a small
 * footer to avoid crowding the primary scan/show actions.
 */
@Composable
fun PairScreen(
    identity: Identity,
    qrBase32: String,
    peerQr: HandshakeQr?,
    onPeerScanned: (HandshakeQr) -> Unit,
    onContinue: () -> Unit,
    onRefreshQr: () -> Unit,
    onContinueToWallet: () -> Unit,
    onShareApp: () -> Unit = {},
    onUpdateFromPeer: () -> Unit = {},
) {
    val qrBitmap = remember(qrBase32) { QrRenderer.render(qrBase32, sizePx = 768) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val scanned = peerQr != null
        Text(
            if (scanned) "Got their code" else "Pair with someone",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (scanned)
                "Keep this screen up so they can scan you. Tap Continue once they have."
            else
                "Show them this code, and point your camera at theirs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // ---- Your QR ----
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Your handshake QR",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            identity.fingerprint.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )

        // ---- Remote pairing: copy / paste codes ----
        val context = LocalContext.current
        var pasteMode by remember { mutableStateOf(false) }
        var pasteText by remember { mutableStateOf("") }
        var pasteError by remember { mutableStateOf<String?>(null) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val clip = android.content.ClipData.newPlainText("wyspr-qr", qrBase32)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    val clipMgr = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipMgr.setPrimaryClip(clip)
                },
                modifier = Modifier.weight(1f),
            ) { Text("Copy my code") }
            OutlinedButton(
                onClick = { pasteMode = !pasteMode },
                modifier = Modifier.weight(1f),
            ) { Text(if (pasteMode) "Use camera" else "Paste peer code") }
        }

        if (pasteMode && !scanned) {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it; pasteError = null },
                placeholder = { Text("Paste their code here…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4,
                isError = pasteError != null,
                supportingText = pasteError?.let { err -> { Text(err) } },
            )
            androidx.compose.material3.Button(
                onClick = {
                    val qr = runCatching {
                        val bytes = HandshakeQrCodec.fromBase32(pasteText.trim())
                        HandshakeQrCodec.decode(bytes)
                    }.getOrNull()
                    if (qr != null) {
                        pasteError = null
                        onPeerScanned(qr)
                    } else {
                        pasteError = "Invalid code — ask them to copy it again."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = pasteText.isNotBlank(),
            ) { Text("Use this code") }
        }

        if (scanned) {
            // Camera intentionally removed from the composition — its
            // DisposableEffect tears down CameraX so we don't keep a
            // live analyzer pegged while the user waits on the peer.
            Text(
                "Scanned them — waiting for them to scan you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        } else if (!pasteMode) {
            // ---- Their QR (camera) ----
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                PeerQrCamera(
                    modifier = Modifier.fillMaxSize(),
                    onScanned = onPeerScanned,
                )
            }
            Text(
                "Looking for their QR…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.padding(top = 4.dp))

        if (scanned) {
            androidx.compose.material3.Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }

        OutlinedButton(
            onClick = onRefreshQr,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh my QR") }

        TextButton(
            onClick = onContinueToWallet,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Skip — I'll pair later") }

        // Sidetrip footer — small links so the primary scan/show
        // actions stay visually dominant.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onShareApp) {
                Text(
                    "Share app",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onUpdateFromPeer) {
                Text(
                    "Update from peer",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
