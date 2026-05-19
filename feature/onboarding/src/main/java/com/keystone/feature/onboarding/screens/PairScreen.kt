package com.keystone.feature.onboarding.screens

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keystone.core.identity.Identity
import com.keystone.core.trust.HandshakeQr
import com.keystone.core.ui.QrRenderer

/**
 * Combined pair-with-peer screen — the user's own QR sits above a
 * live camera scanner so both devices can scan each other without
 * navigating between screens or handing the phone back and forth.
 *
 * Replaces the previous DisplayQr → ScanPeerQr two-screen flow. The
 * camera permission is requested up-front by [PeerQrCamera] so the
 * Inviter doesn't fail on first scan attempt.
 *
 * Sidetrip CTAs (share APK, peer update) are tucked into a small
 * footer to avoid crowding the primary scan/show actions.
 */
@Composable
fun PairScreen(
    identity: Identity,
    qrBase32: String,
    onPeerScanned: (HandshakeQr) -> Unit,
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
        Text(
            "Pair with someone",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
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

        Spacer(modifier = Modifier.padding(top = 4.dp))

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
