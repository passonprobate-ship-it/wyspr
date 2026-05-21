package com.keystone.feature.messaging.mailbox.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.ui.QrRenderer
import com.keystone.feature.messaging.mailbox.MailboxBinding
import com.keystone.feature.messaging.mailbox.MailboxClientViewModel
import com.keystone.feature.messaging.mailbox.MailboxHostViewModel

/**
 * Single combined "Mailbox" screen. Stacks the two roles (Use + Be)
 * vertically so the user can do both from one place — same identity,
 * same trust graph, no reason to hide either behind a separate tile.
 *
 * Use side ("Friends drop messages here when you're offline"):
 *   - If no binding: scan-to-bind button
 *   - If bound: short pub + onion + change/remove
 *
 * Be side ("Hold encrypted messages for your community"):
 *   - Toggle running on/off
 *   - When running: live stats + QR for peers to scan
 *   - Purge button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    clientViewModel: MailboxClientViewModel = hiltViewModel(),
    hostViewModel: MailboxHostViewModel = hiltViewModel(),
) {
    val binding by clientViewModel.myBinding.collectAsStateWithLifecycle()
    val feedback by clientViewModel.scanFeedback.collectAsStateWithLifecycle()
    val running by hostViewModel.hostEnabled.collectAsStateWithLifecycle()
    val bytesHeld by hostViewModel.bytesHeld.collectAsStateWithLifecycle()
    val countHeld by hostViewModel.countHeld.collectAsStateWithLifecycle()
    val recipients by hostViewModel.recipientCount.collectAsStateWithLifecycle()
    val capBytes by hostViewModel.storageCapBytes.collectAsStateWithLifecycle()
    val qrPayload by hostViewModel.mailboxQr.collectAsStateWithLifecycle()

    LaunchedEffect(feedback) {
        if (feedback != null) {
            kotlinx.coroutines.delay(2500)
            clientViewModel.dismissFeedback()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Mailbox", style = MaterialTheme.typography.titleLarge) },
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- Use a mailbox ----
            SectionHeader("Use a mailbox")
            Text(
                "Friends can drop messages here when you're offline. " +
                    "Messages are sealed to your key — your mailbox can't read them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val b = binding
            if (b == null) {
                ConnectCard(onScan = onScan)
            } else {
                BindingCard(
                    binding = b,
                    onRescan = onScan,
                    onRemove = clientViewModel::clearBinding,
                )
            }
            feedback?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- Be a mailbox ----
            SectionHeader("Be a mailbox")
            Text(
                "Hold encrypted messages for your community. You can't read them — " +
                    "every message is sealed to the recipient before it leaves the sender. " +
                    "Costs a bit of storage and bandwidth while running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleCard(running = running, onToggle = hostViewModel::setHostEnabled)
            if (running || countHeld > 0) {
                StatsCard(
                    bytesHeld = bytesHeld,
                    countHeld = countHeld,
                    recipients = recipients,
                    capBytes = capBytes,
                )
            }
            if (running) {
                qrPayload?.let { payload -> QrCard(payload = payload) }
            }
            if (running || countHeld > 0) {
                PurgeCard(running = running, onPurge = hostViewModel::purgeHeld)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
    )
}

// ----- Use a mailbox panels (copied from UseMailboxScreen) -----

@Composable
private fun ConnectCard(onScan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No mailbox configured.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Ask a community member running a mailbox to show you their QR, then scan it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Text("Scan a mailbox QR")
            }
        }
    }
}

@Composable
private fun BindingCard(
    binding: MailboxBinding,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Connected to", style = MaterialTheme.typography.labelMedium)
            Text(
                binding.mailboxPub.bytes.toShortHex(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            val onion = binding.mailboxOnion
            if (onion != null) {
                Text(
                    onion.take(10) + "…" + onion.takeLast(6) + ".onion",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Local only (no .onion advertised)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f)) { Text("Change") }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Remove") }
            }
        }
    }
}

// ----- Be a mailbox panels (copied from BeMailboxScreen) -----

@Composable
private fun ToggleCard(running: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
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
            LinearProgressIndicator(progress = { capProgress }, modifier = Modifier.fillMaxWidth())
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
            // The base32 payload below is a fallback for poor lighting /
            // bad camera — paste it manually on the other device if the
            // scan won't latch.
            Text(
                "Payload: ${payload.length} chars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PurgeCard(running: Boolean, onPurge: () -> Unit) {
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

private fun ByteArray.toShortHex(): String {
    val full = joinToString("") { "%02x".format(it) }
    return full.take(8) + "…" + full.takeLast(8)
}
