package com.keystone.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Inline banner on the Chats tab announcing that one or more paired
 * peers are running a newer Keystone. Tap reveals an "update via
 * peer share" walk-through and a shortcut to the QR-handshake flow
 * (since the peer needs to open Share Keystone on their side).
 *
 * State is owned by [PeerUpdatesViewModel] — periodic Tor poll, in-
 * memory dismiss. Hidden when the list is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerUpdatesBanner(
    onPairPeer: () -> Unit,
    viewModel: PeerUpdatesViewModel = hiltViewModel(),
) {
    val updates by viewModel.available.collectAsStateWithLifecycle()
    val first = updates.firstOrNull() ?: return
    var detailsOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { detailsOpen = true }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val label = if (updates.size == 1) {
                "A paired peer has a newer Keystone (v${first.peerVersionName})"
            } else {
                "${updates.size} peers have newer Keystone builds"
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
        IconButton(onClick = { viewModel.dismiss(first.peerPub) }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("Update Keystone from a peer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your build: v${com.keystone.app.BuildConfig.VERSION_NAME} " +
                            "(${com.keystone.app.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Peers running newer:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    for (u in updates) {
                        Text(
                            "· ${u.peerOnion.take(8)}…${u.peerOnion.takeLast(6)} → " +
                                "v${u.peerVersionName} (${u.peerVersionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        "Ask your peer to open Settings → Share Keystone on their phone. " +
                            "Then tap below to scan their QR.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detailsOpen = false
                    onPairPeer()
                }) { Text("Scan peer's QR") }
            },
            dismissButton = {
                TextButton(onClick = { detailsOpen = false }) { Text("Later") }
            },
        )
    }
}
