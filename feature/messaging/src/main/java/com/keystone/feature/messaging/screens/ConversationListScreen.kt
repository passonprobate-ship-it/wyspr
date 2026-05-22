package com.keystone.feature.messaging.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.keystone.core.identity.GroupId
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.ConversationListViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenThread: (PublicKey) -> Unit,
    onOpenGroup: (GroupId) -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenFindPeers: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val syncing = sync is ConversationListViewModel.SyncBanner.Running
    var fabSheetOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Chats", style = MaterialTheme.typography.titleLarge)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { fabSheetOpen = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Hairline sync indicator at the top of the list — visible
            // only when a round is actually in flight. No banner, no
            // pull-to-refresh, no "Sent N received M" pills. Background
            // auto-sync handles the work; the UI just shows that it's
            // happening.
            if (syncing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when (val s = state) {
                ConversationListViewModel.UiState.Loading -> LoadingPanel()
                ConversationListViewModel.UiState.NoPeers -> EmptyPanel(onOpenFindPeers)
                is ConversationListViewModel.UiState.Ready -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (s.groupRows.isNotEmpty()) {
                            item {
                                Text(
                                    "Groups",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                                )
                            }
                            items(
                                s.groupRows,
                                key = { it.groupId.bytes.contentHashCode() },
                            ) { row ->
                                GroupRowView(
                                    row = row,
                                    onClick = { onOpenGroup(row.groupId) },
                                    onRename = { newName ->
                                        viewModel.renameGroupLocal(row.groupId, newName)
                                    },
                                    onLeave = {
                                        viewModel.leaveGroupLocal(row.groupId)
                                    },
                                )
                            }
                        }
                        if (s.rows.isNotEmpty()) {
                            if (s.groupRows.isNotEmpty()) {
                                item {
                                    Text(
                                        "Direct",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                    )
                                }
                            }
                            items(s.rows, key = { it.peer.bytes.contentHashCode() }) { row ->
                                ThreadRowView(row = row, onClick = { onOpenThread(row.peer) })
                            }
                        }
                    }
                }
            }
        }

        if (fabSheetOpen) {
            NewConversationSheet(
                onDismiss = { fabSheetOpen = false },
                onNewGroup = {
                    fabSheetOpen = false
                    onCreateGroup()
                },
                onFindPeers = {
                    fabSheetOpen = false
                    onOpenFindPeers()
                },
            )
        }
    }
}

/** Bottom sheet shown when the user taps the "+" FAB. WhatsApp's
 *  equivalent is the compose screen; ours offers the two primary
 *  actions: start a new group, or pair with a new peer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationSheet(
    onDismiss: () -> Unit,
    onNewGroup: () -> Unit,
    onFindPeers: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            SheetAction(
                icon = Icons.Filled.Group,
                title = "New group",
                subtitle = "Pick paired peers to message together",
                onClick = onNewGroup,
            )
            SheetAction(
                icon = Icons.Filled.Bluetooth,
                title = "Find peers",
                subtitle = "Scan for nearby Keystone devices to pair",
                onClick = onFindPeers,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyPanel(onOpenFindPeers: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            // Keep the "terminal" feel — a stylised key glyph as a block
            // of monospace text fits the design language better than an
            // illustration.
            Text(
                "—◇—",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "No conversations yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Keystone messages only flow between two devices that have " +
                    "shaken hands in person. Pair with a peer to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExtendedFloatingActionButton(
                onClick = onOpenFindPeers,
                icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                text = { Text("Find a peer") },
                containerColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Compact 4-then-4 fingerprint format: `abcd⋯wxyz`. Long enough to
 * distinguish peers at a glance without dominating the row. Full
 * fingerprint stays visible in the conversation header where the
 * user has reason to read it character-by-character.
 */
private fun shortFingerprint(fp: String): String {
    val raw = fp.filter { it.isLetterOrDigit() }
    if (raw.length < 10) return fp
    return raw.take(4) + "⋯" + raw.takeLast(4)
}

@Composable
private fun FingerprintChip(fingerprint: String) {
    val raw = fingerprint.filter { it.isLetterOrDigit() }
    val mark = raw.take(2).uppercase()
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRowView(
    row: ConversationListViewModel.GroupRow,
    onClick: () -> Unit,
    onRename: (String?) -> Unit = {},
    onLeave: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    if (renameOpen) {
        GroupRenameDialog(
            initial = row.name,
            onDismiss = { renameOpen = false },
            onConfirm = { newName ->
                onRename(newName)
                renameOpen = false
            },
        )
    }
    if (confirmLeave) {
        LeaveGroupDialog(
            groupName = row.name,
            onDismiss = { confirmLeave = false },
            onConfirm = {
                onLeave()
                confirmLeave = false
            },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
    ) {
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    menuOpen = false
                    renameOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text("Leave group") },
                onClick = {
                    menuOpen = false
                    confirmLeave = true
                },
            )
        }
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.name.take(1).uppercase().ifEmpty { "ɢ" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
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
                    val prefix = if (row.lastFromSelf == true) "You: " else ""
                    Text(
                        prefix + preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        "No messages yet",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("Your label for this group") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Local-only — your peers still see the original group name. " +
                        "Leave blank to revert.",
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
private fun LeaveGroupDialog(
    groupName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave \"$groupName\"?") },
        text = {
            Text(
                "Removes this group and all its messages from THIS device. " +
                    "Other members still have the group; you'll re-join " +
                    "only if you accept a fresh membership cert from the " +
                    "creator. There is no undo.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Leave") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ThreadRowView(
    row: ConversationListViewModel.ThreadRow,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FingerprintChip(row.fingerprint.toString())
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name = row.displayName?.takeIf { it.isNotBlank() }
                    if (name != null) {
                        Text(
                            name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            shortFingerprint(row.fingerprint.toString()),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
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
                    val prefix = if (row.lastFromSelf == true) "You: " else ""
                    Text(
                        prefix + preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
}
