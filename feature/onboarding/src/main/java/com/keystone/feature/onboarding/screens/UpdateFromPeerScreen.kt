package com.keystone.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.feature.onboarding.share.PeerUpdateViewModel
import com.keystone.feature.onboarding.share.UpdateChecker

/**
 * Receiver-side UI of the peer-update flow.
 *
 * Layer-1: the user pastes or types the peer's share URL (the same
 * URL their friend's phone is hosting via [ApkShareServer]). The VM
 * fetches `/version.json`, compares against this device's installed
 * version, and offers an Install button if the peer is newer.
 */
@Composable
fun UpdateFromPeerScreen(
    onDone: () -> Unit,
    viewModel: PeerUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Update from a peer", style = MaterialTheme.typography.titleLarge)
        Text(
            "Type or paste the URL shown on the peer's share screen. " +
                "Format is http://<their-ip>:8080. Both phones must be " +
                "on the same WiFi network.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Peer URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
            ),
        )

        when (val s = state) {
            PeerUpdateViewModel.State.Idle -> {
                Button(
                    onClick = { viewModel.check(url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check this peer") }
            }
            is PeerUpdateViewModel.State.Checking -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Checking ${s.url}…", style = MaterialTheme.typography.bodySmall)
            }
            is PeerUpdateViewModel.State.Found -> FoundPanel(
                state = s,
                onInstall = viewModel::install,
                onCheckAgain = { viewModel.reset() },
            )
            is PeerUpdateViewModel.State.Downloading -> DownloadingPanel(s)
            is PeerUpdateViewModel.State.Installing -> InstallingPanel(s)
            is PeerUpdateViewModel.State.Failed -> FailedPanel(
                message = s.message,
                onRetry = { viewModel.reset() },
            )
        }

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}

@Composable
private fun FoundPanel(
    state: PeerUpdateViewModel.State.Found,
    onInstall: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    val sha256Pretty = remember(state.peer.apkSha256) {
        state.peer.apkSha256.chunked(8).joinToString(" ")
    }
    val sizeMb = remember(state.peer.apkSizeBytes) {
        "%.1f".format(state.peer.apkSizeBytes / (1024.0 * 1024.0))
    }
    Surface(
        color = when (state.comparison) {
            PeerUpdateViewModel.State.Comparison.Newer ->
                MaterialTheme.colorScheme.primaryContainer
            PeerUpdateViewModel.State.Comparison.Same,
            PeerUpdateViewModel.State.Comparison.Older ->
                MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (state.comparison) {
                    PeerUpdateViewModel.State.Comparison.Newer -> "Update available"
                    PeerUpdateViewModel.State.Comparison.Same -> "Already up to date"
                    PeerUpdateViewModel.State.Comparison.Older -> "Peer is on an older version"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Peer: Keystone ${state.peer.versionName} " +
                    "(versionCode ${state.peer.versionCode})  •  $sizeMb MB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "You: versionCode ${state.localVersionCode}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("SHA-256", style = MaterialTheme.typography.labelSmall)
            Text(sha256Pretty, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (state.comparison == PeerUpdateViewModel.State.Comparison.Newer) {
        Button(
            onClick = onInstall,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Download and install") }
    }
    OutlinedButton(
        onClick = onCheckAgain,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Check a different peer") }
}

@Composable
private fun DownloadingPanel(state: PeerUpdateViewModel.State.Downloading) {
    val ratio = if (state.total > 0) state.bytesRead.toFloat() / state.total else 0f
    val mbRead = "%.1f".format(state.bytesRead / (1024.0 * 1024.0))
    val mbTotal = "%.1f".format(state.total / (1024.0 * 1024.0))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Downloading ${state.peer.versionName}…", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = ratio.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
        Text(
            "$mbRead / $mbTotal MB",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Verifying SHA-256 as it streams. Don't switch away until this finishes.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun InstallingPanel(state: PeerUpdateViewModel.State.Installing) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Installer launched",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Android's package installer will prompt you to confirm. " +
                    "If it doesn't appear, check your notification shade.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${state.peer.versionName}",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FailedPanel(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    OutlinedButton(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Try again") }
}
