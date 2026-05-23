package com.wyspr.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyspr.core.identity.Fingerprint
import com.wyspr.core.trust.HandshakeSession
import com.wyspr.core.trust.TrustEdge

/**
 * Terminal screen for the onboarding flow. Two shapes:
 *
 *   - Success — shows the new trust edge's metadata and a CTA to the home tab
 *   - Aborted — explains the failure reason and offers retry where it's safe
 *
 * Aborts with [HandshakeSession.AbortReason.FingerprintMismatch] or
 * [HandshakeSession.AbortReason.ChannelBindingMismatch] do NOT offer
 * retry — the peer is quarantined for 24 hours and the user is told to
 * restart from a fresh QR.
 */
@Composable
fun ResultScreen(
    outcome: HandshakeSession.Outcome,
    onHome: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (outcome) {
            is HandshakeSession.Outcome.Committed -> SuccessCard(outcome.edge, onHome)
            is HandshakeSession.Outcome.Aborted -> AbortCard(outcome.reason, onRetry, onHome)
        }
    }
}

@Composable
private fun SuccessCard(edge: TrustEdge, onHome: () -> Unit) {
    Surface(
        color = Color(0xFF143A2B),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Trust established", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF80E0C0))
            Text(
                "You and your peer are now connected via a signed invitation " +
                    "certificate. The trust edge is stored in your encrypted DB.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Peer fingerprint", style = MaterialTheme.typography.labelLarge)
            Text(
                Fingerprint.of(edge.to.bytes).toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                "Vouch level: ${edge.vouchLevel.name}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}

@Composable
private fun AbortCard(
    reason: HandshakeSession.AbortReason,
    onRetry: () -> Unit,
    onHome: () -> Unit,
) {
    val (title, body, canRetry) = when (reason) {
        HandshakeSession.AbortReason.QrStale -> Triple(
            "QR expired",
            "Both QRs must be less than 5 minutes old. Refresh and try again.",
            true,
        )
        HandshakeSession.AbortReason.FingerprintMismatch -> Triple(
            "Fingerprints did not match",
            "This is the alarm signal for a relay attack. The peer is " +
                "quarantined for 24 hours. Use a fresh QR from a peer you " +
                "are physically next to and try again later.",
            false,
        )
        HandshakeSession.AbortReason.ChannelBindingMismatch -> Triple(
            "Cryptographic mismatch",
            "The peer's identity key did not match what their QR claimed. " +
                "Treat this as a MITM. Peer is quarantined for 24 hours.",
            false,
        )
        HandshakeSession.AbortReason.SignatureInvalid -> Triple(
            "Invalid certificate",
            "The inviter's signature did not verify. The peer is " +
                "quarantined for 24 hours.",
            false,
        )
        HandshakeSession.AbortReason.CertificateExpired -> Triple(
            "Certificate expired",
            "Your peer's certificate is past its validity window. They " +
                "must mint a new one and reissue.",
            true,
        )
        HandshakeSession.AbortReason.UserCancelled -> Triple(
            "Cancelled",
            "You cancelled the handshake. No trust edge was created.",
            true,
        )
        HandshakeSession.AbortReason.TransportFailed -> Triple(
            "Couldn't reach the other device",
            "Check that one of you tapped \"Start a new community\" and the " +
                "other tapped \"Join with a QR code\" — both sides picking the " +
                "same role will leave the handshake waiting forever. Also make " +
                "sure both devices are within a few feet, Bluetooth is on, and " +
                "you've granted the Nearby Devices permission.",
            true,
        )
        HandshakeSession.AbortReason.NotAuthorized -> Triple(
            "Not authorized to invite",
            "Only the community founder, or a member who has been promoted " +
                "to Full trust by ≥ 2 independent paths, can issue " +
                "invitations. This device has neither status in the " +
                "active community.",
            false,
        )
        HandshakeSession.AbortReason.PeerQuarantined -> Triple(
            "Peer is in cooldown",
            "This peer's previous handshake against you aborted. They are " +
                "quarantined for 24 hours. Wait the cooldown, then try again " +
                "with a fresh QR.",
            false,
        )
        HandshakeSession.AbortReason.CertReplayed -> Triple(
            "Replayed certificate",
            "The invitation you received was already accepted once on this " +
                "device. Ask your peer to mint a fresh one.",
            false,
        )
    }

    Surface(
        color = Color(0xFF3A1414),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color(0xFFE08080))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Start,
            )
        }
    }
    if (canRetry) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    }
    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Back to start") }
}
