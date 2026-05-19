package com.keystone.feature.marketplace.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.transport.TorBackend
import com.keystone.core.trust.TrustLevel
import com.keystone.core.ui.components.BadgeStatus
import com.keystone.core.ui.components.TrustBadge
import com.keystone.feature.marketplace.CommunityViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Maps a [TrustLevel] to the visual conventions of [TrustBadge].
 * Roots and Full members render as the strongest tone (Verified);
 * Provisional as the in-progress tone (Live, with subtle pulse);
 * Quarantined as the warning tone; Unknown as Neutral.
 */
private fun TrustLevel.toBadge(): Pair<String, BadgeStatus> = when (this) {
    TrustLevel.Root -> "Root" to BadgeStatus.Verified
    TrustLevel.Full -> "Full" to BadgeStatus.Verified
    TrustLevel.Provisional -> "Provisional" to BadgeStatus.Live
    TrustLevel.Quarantined -> "Quarantined" to BadgeStatus.Quarantined
    TrustLevel.Unknown -> "Unknown" to BadgeStatus.Neutral
}

@Composable
private fun TrustLevelBadge(level: TrustLevel) {
    val (label, status) = level.toBadge()
    TrustBadge(label = label, status = status)
}

/**
 * "My community" — read-only view of the local trust graph. Shows
 * the device's own identity + role, the community id, and the list
 * of peers paired with this device so far. Tapping the community id
 * copies it to the clipboard (the user might want to paste it on
 * another device when adding a second pairing).
 */
@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    onOpenGraph: () -> Unit = {},
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    val onionAddress by viewModel.onionAddress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("My community", style = MaterialTheme.typography.headlineMedium)

        TorStatusRow(torState, onionAddress)

        when (val s = state) {
            CommunityViewModel.UiState.Loading -> LoadingPanel()
            CommunityViewModel.UiState.NoCommunity -> EmptyPanel()
            is CommunityViewModel.UiState.Ready -> ReadyPanel(s)
        }

        OutlinedButton(
            onClick = onOpenGraph,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("View trust graph") }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}

@Composable
private fun TorStatusRow(state: TorBackend.State, onionAddress: String?) {
    val (label, statusColor) = when (state) {
        TorBackend.State.Idle -> "Tor: starting…" to MaterialTheme.colorScheme.outline
        is TorBackend.State.Bootstrapping ->
            "Tor: bootstrapping ${state.percent}%" to MaterialTheme.colorScheme.tertiary
        TorBackend.State.Ready -> "Tor: ready — reachable anywhere" to MaterialTheme.colorScheme.primary
        is TorBackend.State.Failed -> "Tor: ${state.message}" to MaterialTheme.colorScheme.error
        TorBackend.State.Unavailable -> "Tor: unavailable on this build" to MaterialTheme.colorScheme.outline
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = statusColor,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(8.dp),
                ) {}
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The .onion is derived deterministically from the keystore,
            // so it's known well before Tor has finished bootstrapping —
            // showing it during Bootstrapping is intentional. We elide
            // the middle so the address fits one line on phone widths.
            val onion = onionAddress
            if (onion != null) {
                val display = onion.take(10) + "…" + onion.takeLast(6) + ".onion"
                Text(
                    display,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingPanel() {
    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun EmptyPanel() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "No community on this device. Complete an onboarding " +
                "handshake first.",
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReadyPanel(state: CommunityViewModel.UiState.Ready) {
    SelfCard(state)
    CommunityIdRow(hex = state.communityIdHex, foundedAt = state.foundedAt)
    StatsRow(peerCount = state.peers.size)

    Text(
        if (state.peers.isEmpty()) "No paired peers yet"
        else "Paired peers (${state.peers.size})",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(state.peers, key = { it.publicKey.bytes.toList() }) { peer ->
            PeerRow(peer)
        }
    }
}

@Composable
private fun SelfCard(state: CommunityViewModel.UiState.Ready) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "You",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                TrustLevelBadge(level = state.ownTrustLevel)
            }
            Text(
                state.ownFingerprint.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (state.isFounder) "Community founder" else "Community member",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun CommunityIdRow(hex: String, foundedAt: Long) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shortHex = hex.take(8) + "…" + hex.takeLast(8)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(hex))
                android.widget.Toast.makeText(
                    context,
                    "Community ID copied",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Community ID — tap to copy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                shortHex,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Founded " + DateFormat.getDateInstance().format(Date(foundedAt * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StatsRow(peerCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatBox(label = "Paired peers", value = peerCount.toString())
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun PeerRow(peer: CommunityViewModel.PeerEntry) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrustLevelBadge(level = peer.trustLevel)
                }
                Text(
                    peer.fingerprint.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    "Paired " + DateFormat.getDateInstance().format(Date(peer.pairedAt * 1000)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Peer's HSv3 onion (Sprint 3+) — proves we have a way
                // to reach this peer over Tor when BLE isn't available.
                // Elide the middle so the 56-char address fits one line.
                val onion = peer.peerOnion
                if (onion != null) {
                    val short = onion.take(8) + "…" + onion.takeLast(6) + ".onion"
                    Text(
                        "via Tor: $short",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
