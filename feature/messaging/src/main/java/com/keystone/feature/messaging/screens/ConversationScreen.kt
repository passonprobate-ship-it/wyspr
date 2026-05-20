package com.keystone.feature.messaging.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.ConversationViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationScreen(
    peer: PublicKey,
    onBack: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    LaunchedEffect(peer.bytes.toList()) { viewModel.bind(peer) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var renameDialogOpen by remember { mutableStateOf(false) }

    // Pull the current display name from Ready state — falls back to
    // null while loading so the header just shows the fingerprint
    // until the contact feed delivers.
    val currentDisplayName: String? = (state as? ConversationViewModel.UiState.Ready)?.displayName

    if (renameDialogOpen) {
        RenameContactDialog(
            initial = currentDisplayName.orEmpty(),
            onDismiss = { renameDialogOpen = false },
            onConfirm = { newName ->
                viewModel.renameContact(newName)
                renameDialogOpen = false
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { renameDialogOpen = true },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                val name = currentDisplayName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        peer.fingerprint.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        peer.fingerprint.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Tap to add a name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                ConversationViewModel.UiState.Loading -> {
                    Text(
                        "Loading…",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ConversationViewModel.UiState.Ready -> {
                    LaunchedEffect(s.messages.size) {
                        if (s.messages.isNotEmpty()) {
                            listState.animateScrollToItem(s.messages.lastIndex)
                        }
                    }
                    if (s.messages.isEmpty()) {
                        EmptyThread()
                    } else {
                        val ownBytes = s.own?.bytes
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(s.messages, key = { it.id.toList() }) { msg ->
                                MessageBubble(
                                    msg = msg,
                                    fromSelf = ownBytes?.contentEquals(msg.fromPub) == true,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        viewModel.send(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun RenameContactDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("e.g. Sam") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Shown only on this device. Leave blank to clear the name.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EmptyThread() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Say hello — your first message will land here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity, fromSelf: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromSelf) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    msg.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.createdAt * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fromSelf) {
                        Text(
                            when (msg.status) {
                                "pending" -> "queued"
                                "sent" -> "sent ✓"
                                "delivered" -> "delivered ✓✓"
                                "read" -> "read ✓✓"
                                else -> msg.status
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}
