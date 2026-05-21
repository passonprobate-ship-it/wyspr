package com.keystone.feature.messaging.mailbox.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.feature.messaging.mailbox.MailboxBinding
import com.keystone.feature.messaging.mailbox.MailboxClientViewModel

/**
 * "Use a mailbox" screen. If the user already has a mailbox
 * configured, show it. Otherwise show a button to scan a host's QR.
 *
 * The screen does NOT do the pull — that happens automatically inside
 * every sync round in [com.keystone.feature.messaging.sync.MessageSyncEngine.mailboxPullPhase].
 * The button just sets the binding the engine consults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UseMailboxScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    viewModel: MailboxClientViewModel = hiltViewModel(),
) {
    val binding by viewModel.myBinding.collectAsStateWithLifecycle()
    val feedback by viewModel.scanFeedback.collectAsStateWithLifecycle()

    LaunchedEffect(feedback) {
        if (feedback != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.dismissFeedback()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Use a mailbox", style = MaterialTheme.typography.titleLarge) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Friends can drop messages here when you're offline. " +
                    "Messages are sealed to your key — the mailbox can't read them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (binding == null) {
                ConnectCard(onScan = onScan)
            } else {
                BindingCard(
                    binding = binding!!,
                    onRescan = onScan,
                    onRemove = viewModel::clearBinding,
                )
            }

            feedback?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(onScan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "No mailbox configured.",
                style = MaterialTheme.typography.bodyLarge,
            )
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
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.weight(1f),
                ) { Text("Change") }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                ) { Text("Remove") }
            }
        }
    }
}

private fun ByteArray.toShortHex(): String {
    val full = joinToString("") { "%02x".format(it) }
    return full.take(8) + "…" + full.takeLast(8)
}
