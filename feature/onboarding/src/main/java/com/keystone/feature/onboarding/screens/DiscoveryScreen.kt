package com.keystone.feature.onboarding.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.bluetooth.BlePermissions
import com.keystone.feature.onboarding.DiscoveryViewModel

@Composable
fun DiscoveryScreen(
    onBack: () -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(BlePermissions.allGranted(context))
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionsGranted = grants.all { it.value }
        viewModel.refreshStatus()
    }

    DisposableEffect(Unit) {
        viewModel.refreshStatus()
        onDispose {
            // Always stop on leave so we don't burn battery scanning
            // when the user has navigated away.
            viewModel.stopScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Find nearby peers", style = MaterialTheme.typography.titleLarge)
        Text(
            "Scans for other Keystone devices in range that advertise the " +
                "same community. Discovery is the first step of the handshake; " +
                "the actual key exchange runs in a future sprint.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when {
            !permissionsGranted -> PermissionPrompt(
                missing = BlePermissions.missing(context),
                onRequest = { permLauncher.launch(BlePermissions.required.toTypedArray()) },
            )
            !state.bluetoothReady -> BluetoothOffPrompt()
            state.scanning -> Button(
                onClick = viewModel::stopScan,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop scanning") }
            else -> Button(
                onClick = viewModel::startScan,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start scanning") }
        }

        state.error?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (state.scanning) {
            Row(scanning = true)
        }

        HorizontalDivider()
        Text(
            "${state.peers.size} peer(s) seen",
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.peers.isEmpty()) {
            Text(
                if (state.scanning) "Looking..." else "Tap Start to scan.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.peers) { PeerRow(it) }
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}

@Composable
private fun PermissionPrompt(missing: List<String>, onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Bluetooth permission needed",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Keystone scans and advertises over Bluetooth Low Energy. " +
                    "We don't request location — the OS asks because pre-Android-12 " +
                    "scanning was tied to it. Missing: ${missing.joinToString()}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Bluetooth access")
            }
        }
    }
}

@Composable
private fun BluetoothOffPrompt() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Bluetooth is off",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Turn it on in system settings, then come back.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PeerRow(peer: PeerEndpoint) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            peer.opaqueAddress,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            peer.kind.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Row(scanning: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (scanning) {
            CircularProgressIndicator()
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun OutlinedRow() { /* placeholder kept for future tweak */ }
