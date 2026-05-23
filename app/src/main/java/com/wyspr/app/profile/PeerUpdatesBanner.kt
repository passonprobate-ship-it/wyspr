package com.wyspr.app.profile

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerUpdatesBanner(
    onUpdateFromPeer: () -> Unit,
    viewModel: PeerUpdatesViewModel = hiltViewModel(),
) {
    val updates by viewModel.available.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
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
                "A paired peer has a newer Wyspr (v${first.peerVersionName})"
            } else {
                "${updates.size} peers have newer Wyspr builds"
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
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("Update Wyspr from a peer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your build: v${com.wyspr.app.BuildConfig.VERSION_NAME} " +
                            "(${com.wyspr.app.BuildConfig.VERSION_CODE})",
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

                    when (val ds = downloadState) {
                        PeerUpdatesViewModel.DownloadState.Idle -> {
                            if (first.canDownload) {
                                val sizeMb = remember(first.apkSizeBytes) {
                                    "%.1f".format(first.apkSizeBytes / (1024.0 * 1024.0))
                                }
                                Button(
                                    onClick = { viewModel.startTorDownload(first) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Download over Tor ($sizeMb MB)") }
                            }
                            Text(
                                "Or: ask your peer to open Settings → Share Wyspr, " +
                                    "then tap below to scan their QR.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is PeerUpdatesViewModel.DownloadState.Downloading -> {
                            val ratio = if (ds.total > 0) ds.bytesRead.toFloat() / ds.total else 0f
                            val mbRead = "%.1f".format(ds.bytesRead / (1024.0 * 1024.0))
                            val mbTotal = "%.1f".format(ds.total / (1024.0 * 1024.0))
                            LinearProgressIndicator(
                                progress = { ratio.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Downloading over Tor: $mbRead / $mbTotal MB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "SHA-256 verified as it streams. This may take a few minutes.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        PeerUpdatesViewModel.DownloadState.Installing -> {
                            Text(
                                "Download complete — Android installer launched.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        PeerUpdatesViewModel.DownloadState.NeedsPermission -> {
                            Text(
                                "Wyspr needs permission to install apps. " +
                                    "Open Settings, toggle \"Allow from this source\", " +
                                    "then try again.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            viewModel.installPermissionSettingsIntent(),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open settings") }
                            OutlinedButton(
                                onClick = { viewModel.retryPermission() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Try again") }
                        }
                        is PeerUpdatesViewModel.DownloadState.Failed -> {
                            Text(
                                ds.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = { viewModel.resetDownload() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Dismiss") }
                        }
                    }
                }
            },
            confirmButton = {
                if (downloadState is PeerUpdatesViewModel.DownloadState.Idle) {
                    TextButton(onClick = {
                        detailsOpen = false
                        onUpdateFromPeer()
                    }) { Text("Update from peer (LAN)") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    detailsOpen = false
                    viewModel.resetDownload()
                }) { Text(if (downloadState is PeerUpdatesViewModel.DownloadState.Idle) "Later" else "Close") }
            },
        )
    }
}
