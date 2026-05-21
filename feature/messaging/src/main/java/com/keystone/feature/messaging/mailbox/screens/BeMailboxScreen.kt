package com.keystone.feature.messaging.mailbox.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.ui.QrRenderer
import com.keystone.feature.messaging.mailbox.MailboxHostViewModel

/**
 * "Be a mailbox" screen — toggle hosting on, see live storage stats,
 * show a QR for peers to scan, stop / purge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeMailboxScreen(
    onBack: () -> Unit,
    viewModel: MailboxHostViewModel = hiltViewModel(),
) {
    val running by viewModel.hostEnabled.collectAsStateWithLifecycle()
    val bytesHeld by viewModel.bytesHeld.collectAsStateWithLifecycle()
    val countHeld by viewModel.countHeld.collectAsStateWithLifecycle()
    val recipients by viewModel.recipientCount.collectAsStateWithLifecycle()
    val capBytes by viewModel.storageCapBytes.collectAsStateWithLifecycle()
    val qrPayload by viewModel.mailboxQr.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Be a mailbox", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToggleCard(
                running = running,
                onToggle = viewModel::setHostEnabled,
            )
            if (running || countHeld > 0) {
                StatsCard(
                    bytesHeld = bytesHeld,
                    countHeld = countHeld,
                    recipients = recipients,
                    capBytes = capBytes,
                )
            }
            if (running) {
                qrPayload?.let { payload ->
                    QrCard(payload = payload)
                }
            }
            ActionsCard(
                running = running,
                onPurge = viewModel::purgeHeld,
            )
        }
    }
}

@Composable
private fun ToggleCard(running: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (running) "Running" else "Stopped",
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = running, onCheckedChange = onToggle)
            }
            Text(
                "Hold encrypted messages for your community. You can't read them — " +
                    "every message is sealed to the recipient before it leaves the sender. " +
                    "Costs a bit of storage and bandwidth while running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsCard(
    bytesHeld: Long,
    countHeld: Int,
    recipients: Int,
    capBytes: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Holding", style = MaterialTheme.typography.labelLarge)
            Text(
                buildString {
                    append(countHeld)
                    append(if (countHeld == 1) " message" else " messages")
                    append(" for ")
                    append(recipients)
                    append(if (recipients == 1) " peer" else " peers")
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val capProgress = if (capBytes > 0L) (bytesHeld.toFloat() / capBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { capProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${formatBytes(bytesHeld)} of ${formatBytes(capBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QrCard(payload: String) {
    val qrBitmap = remember(payload) { QrRenderer.render(payload, sizePx = 768) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Show this QR to peers who want to use you as their mailbox",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Mailbox QR",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ActionsCard(running: Boolean, onPurge: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stored messages will be deleted permanently.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPurge, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Purge held messages" else "Clear remaining storage")
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
}
