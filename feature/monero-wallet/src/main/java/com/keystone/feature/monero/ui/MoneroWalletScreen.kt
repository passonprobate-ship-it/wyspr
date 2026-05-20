package com.keystone.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.MoneroWalletService.ConnectionStatus

/**
 * v0.7.0a Monero wallet screen.
 *
 * What it shows:
 *  - Connection status: waiting for Tor / connected / unreachable.
 *  - When connected: the remote node label, chain tip, sync flag.
 *  - A "wallet engine not yet bundled" panel — the on-device
 *    balance/history rows that come with the JNI binding in
 *    v0.7.0b.
 *
 * What it deliberately does not show:
 *  - A balance number. Surfacing a "0 XMR" until the engine lands
 *    would be misleading.
 *  - A send button. Same reasoning.
 *  - The user's own Monero address. We don't have one to derive
 *    until the engine lands.
 */
@Composable
fun MoneroWalletScreen(
    state: ConnectionStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Monero",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "All network traffic routes through Keystone's embedded Tor proxy. " +
                "The remote node operator sees a Tor exit, never your IP.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionPanel(state = state, onRefresh = onRefresh)

        EngineNotBundledPanel()
    }
}

@Composable
private fun ConnectionPanel(
    state: ConnectionStatus,
    onRefresh: () -> Unit,
) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Remote node",
                style = MaterialTheme.typography.titleMedium,
            )
            when (state) {
                ConnectionStatus.Idle -> {
                    Text(
                        text = "Initializing…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ConnectionStatus.WaitingForTor -> {
                    Text(
                        text = "Waiting for Tor to finish bootstrapping. Hold tight — " +
                            "first-time circuit build can take up to a minute.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ConnectionStatus.Connected -> {
                    Text(
                        text = state.node.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${state.node.host}:${state.node.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    KeyValue("Chain tip", "${state.chainHeight}")
                    if (state.targetHeight > 0L && state.targetHeight != state.chainHeight) {
                        KeyValue("Target height", "${state.targetHeight}")
                    }
                    KeyValue("Node synchronized", if (state.daemonSynchronized) "yes" else "no")
                    KeyValue("Network", state.nettype)
                    KeyValue("Daemon version", state.version)
                }
                ConnectionStatus.AllNodesUnreachable -> {
                    Text(
                        text = "Every node in the default pool was unreachable through Tor. " +
                            "Check that Tor has bootstrapped, or try again — public nodes " +
                            "rotate frequently.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun EngineNotBundledPanel() {
    KeystonePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.tertiary,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "On-device wallet engine: not bundled",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "Balance, history, receive, and send all require a native crypto " +
                    "engine that scans blocks and signs transactions locally. The engine " +
                    "lands in v0.7.0b once the GPL-licensed Monero JNI binding is wired in.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Until then, this screen is a connectivity probe — proof the Tor leg " +
                    "and the remote-node RPC plumbing work end-to-end on real hardware.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
