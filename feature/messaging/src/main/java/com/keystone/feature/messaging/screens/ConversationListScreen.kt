package com.keystone.feature.messaging.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.ConversationListViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationListScreen(
    onOpenThread: (PublicKey) -> Unit,
    onBack: () -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Messages", style = MaterialTheme.typography.headlineMedium)

        SyncBannerView(banner = sync, onDismiss = viewModel::dismissSyncBanner)

        Button(
            onClick = viewModel::syncNow,
            enabled = sync !is ConversationListViewModel.SyncBanner.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (sync) {
                    ConversationListViewModel.SyncBanner.Running -> "Syncing…"
                    else -> "Sync now"
                },
            )
        }

        when (val s = state) {
            ConversationListViewModel.UiState.Loading ->
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            ConversationListViewModel.UiState.NoPeers -> EmptyPanel()
            is ConversationListViewModel.UiState.Ready ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(s.rows, key = { it.peer.bytes.toList() }) { row ->
                        ThreadRowView(row = row, onClick = { onOpenThread(row.peer) })
                    }
                }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}

@Composable
private fun SyncBannerView(
    banner: ConversationListViewModel.SyncBanner?,
    onDismiss: () -> Unit,
) {
    when (banner) {
        null -> Unit
        ConversationListViewModel.SyncBanner.Running -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Looking for paired peers…", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is ConversationListViewModel.SyncBanner.Done -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
            ) {
                Text(
                    "Sync complete — sent ${banner.pushed}, received ${banner.received}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        is ConversationListViewModel.SyncBanner.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
            ) {
                Text(
                    banner.message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun EmptyPanel() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "No paired peers yet. Complete an onboarding handshake first " +
                "— Keystone messages only flow between two devices that " +
                "have shaken hands in person.",
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ThreadRowView(
    row: ConversationListViewModel.ThreadRow,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    row.fingerprint.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                row.lastAt?.let {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val preview = row.lastBodyPreview
            if (preview != null) {
                val prefix = when (row.lastFromSelf) {
                    true -> "You: "
                    else -> ""
                }
                Text(
                    prefix + preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tap to start chatting",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
