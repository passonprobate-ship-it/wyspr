package com.wyspr.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyspr.core.identity.Fingerprint
import com.wyspr.core.ui.WysprAccent
import com.wyspr.core.ui.components.WysprPanel

/**
 * Visual-channel binding for the handshake. Both users read both
 * fingerprints out loud and confirm character-for-character. A
 * mismatch is the alarm signal for a relay attack: peer is
 * quarantined for 24h and the user is told to start over.
 *
 * SECURITY-MODEL.md §3.3.
 */
@Composable
fun CompareFingerprintsScreen(
    mine: Fingerprint,
    theirs: Fingerprint,
    onMatch: () -> Unit,
    onNoMatch: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Compare fingerprints",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Read both fingerprints out loud with your peer. They must match " +
                "exactly. If even one group differs, abort — that's the relay-" +
                "attack alarm.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        FingerprintPanel(
            label = "YOURS",
            fingerprint = mine,
            accent = WysprAccent.Inviter,
        )
        FingerprintPanel(
            label = "THEIR DEVICE",
            fingerprint = theirs,
            accent = WysprAccent.Invitee,
        )

        Button(
            onClick = onMatch,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("They match — continue") }

        OutlinedButton(
            onClick = onNoMatch,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Not match — abort and quarantine") }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel onboarding")
        }
    }
}

@Composable
private fun FingerprintPanel(
    label: String,
    fingerprint: Fingerprint,
    accent: androidx.compose.ui.graphics.Color,
) {
    WysprPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            Text(
                fingerprint.toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}
