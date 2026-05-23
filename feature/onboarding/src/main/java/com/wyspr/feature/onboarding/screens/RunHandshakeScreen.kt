package com.wyspr.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyspr.core.trust.HandshakeSession
import com.wyspr.core.ui.WysprAccent
import com.wyspr.core.ui.components.BadgeStatus
import com.wyspr.core.ui.components.WysprPanel
import com.wyspr.core.ui.components.StepIndicator
import com.wyspr.core.ui.components.TrustBadge

/**
 * Live-progress view while the Noise XX handshake runs over BLE.
 * Each [HandshakeSession.State] maps to a captioned step. Cancellation
 * aborts the session and applies the 24h quarantine.
 */
@Composable
fun RunHandshakeScreen(
    state: HandshakeSession.State,
    onCancel: () -> Unit,
) {
    val step = stepFor(state)
    val caption = captionFor(state)
    val isTerminal = state == HandshakeSession.State.Committed ||
        state == HandshakeSession.State.Aborted
    val badgeStatus = when (state) {
        HandshakeSession.State.Committed -> BadgeStatus.Verified
        HandshakeSession.State.Aborted -> BadgeStatus.Quarantined
        else -> BadgeStatus.Live
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Establishing trust",
            style = MaterialTheme.typography.headlineSmall,
        )
        StepIndicator(
            totalSteps = 4,
            currentStep = step,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        if (!isTerminal) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = WysprAccent.Verified,
                strokeWidth = 3.dp,
            )
        }

        WysprPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TrustBadge(label = badgeLabel(state), status = badgeStatus)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel — quarantines this peer for 24h")
        }
    }
}

private fun stepFor(state: HandshakeSession.State): Int = when (state) {
    HandshakeSession.State.AwaitingTransport -> 0
    HandshakeSession.State.NoiseHandshakeInProgress -> 1
    HandshakeSession.State.ExchangingCertificates -> 2
    HandshakeSession.State.Committed -> 3
    HandshakeSession.State.Aborted -> 3
}

private fun captionFor(state: HandshakeSession.State): String = when (state) {
    HandshakeSession.State.AwaitingTransport ->
        "Connecting to your peer over Bluetooth…"
    HandshakeSession.State.NoiseHandshakeInProgress ->
        "Establishing an encrypted channel (Noise XX)…"
    HandshakeSession.State.ExchangingCertificates ->
        "Exchanging signed invitation certificates…"
    HandshakeSession.State.Committed ->
        "Trust edge written. Finalising."
    HandshakeSession.State.Aborted ->
        "Handshake aborted."
}

private fun badgeLabel(state: HandshakeSession.State): String = when (state) {
    HandshakeSession.State.AwaitingTransport -> "CONNECTING"
    HandshakeSession.State.NoiseHandshakeInProgress -> "NOISE XX"
    HandshakeSession.State.ExchangingCertificates -> "VOUCHING"
    HandshakeSession.State.Committed -> "VERIFIED"
    HandshakeSession.State.Aborted -> "ABORTED"
}
