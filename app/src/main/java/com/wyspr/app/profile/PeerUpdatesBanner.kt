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
        Text(
            if (updates.size == 1) "Wyspr v${first.peerVersionName} available"
            else "${updates.size} peers have newer builds",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
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
            onDismissRequest = {
                detailsOpen = false
                viewModel.resetDownload()
            },
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "You have v${com.wyspr.app.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (u in updates) {
                        Text(
                            "${u.peerOnion.take(8)}… has v${u.peerVersionName}",
                            style = MaterialTheme.typography.bodyMedium,
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
                                ) { Text("Download via Tor ($sizeMb MB)") }
                            }
                            OutlinedButton(
                                onClick = {
                                    detailsOpen = false
                                    onUpdateFromPeer()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Update via LAN") }
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
                                "Downloading: $mbRead / $mbTotal MB (verified)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        PeerUpdatesViewModel.DownloadState.Installing -> {
                            Text(
                                "Download complete. System installer opening…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        PeerUpdatesViewModel.DownloadState.NeedsPermission -> {
                            Text(
                                "Allow Wyspr to install apps in system Settings, then retry.",
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
                            ) { Text("Retry") }
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
                            ) { Text("Try again") }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    detailsOpen = false
                    viewModel.resetDownload()
                }) { Text("Close") }
            },
        )
    }
}
