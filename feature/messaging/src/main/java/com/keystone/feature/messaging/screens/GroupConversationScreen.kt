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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.database.entities.GroupMessageEntity
import com.keystone.core.identity.GroupId
import com.keystone.feature.messaging.GroupConversationViewModel
import com.keystone.feature.messaging.location.LocationPayload
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupConversationScreen(
    groupId: GroupId,
    onBack: () -> Unit,
    viewModel: GroupConversationViewModel = hiltViewModel(),
) {
    LaunchedEffect(groupId.bytes.toList()) { viewModel.bind(groupId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var memberDialogOpen by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var members by remember { mutableStateOf<List<GroupConversationViewModel.MemberView>>(emptyList()) }

    LaunchedEffect(memberDialogOpen) {
        if (memberDialogOpen) members = viewModel.loadMembers()
    }

    if (memberDialogOpen) {
        MemberListDialog(
            members = members,
            onDismiss = { memberDialogOpen = false },
        )
    }
    if (renameDialogOpen) {
        val current = (state as? GroupConversationViewModel.UiState.Ready)?.groupName.orEmpty()
        RenameGroupDialog(
            initial = current,
            onDismiss = { renameDialogOpen = false },
            onConfirm = { newName ->
                viewModel.renameLocal(newName)
                renameDialogOpen = false
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val s = state as? GroupConversationViewModel.UiState.Ready
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            s?.groupName ?: "Group",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                        )
                        s?.let {
                            Text(
                                "${it.memberCount} members",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { memberDialogOpen = true },
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renameDialogOpen = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename group locally")
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
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    GroupConversationViewModel.UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    is GroupConversationViewModel.UiState.Ready -> {
                        val listState = rememberLazyListState()
                        // Scroll to bottom whenever the message count grows.
                        // delay(50) lets the LazyColumn measure its items
                        // first — without it, the initial open of a thread
                        // with pre-existing messages tries to scroll before
                        // layout exists and lands at index 0 (so the user
                        // sees the OLDEST messages instead of the newest).
                        LaunchedEffect(s.messages.size) {
                            if (s.messages.isNotEmpty()) {
                                kotlinx.coroutines.delay(50)
                                listState.scrollToItem(s.messages.lastIndex)
                            }
                        }
                        if (s.messages.isEmpty()) {
                            EmptyGroupThread()
                        } else {
                            val ownBytes = s.ownPub?.bytes
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                items(s.messages, key = { it.id.toList() }) { msg ->
                                    GroupMessageBubble(
                                        msg = msg,
                                        fromSelf = ownBytes?.contentEquals(msg.fromPub) == true,
                                        senderDisplayName = s.displayNames[msg.fromPub.toList()],
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            val shareLocation = rememberLocationShareController { lat, lng, acc ->
                viewModel.sendLocation(lat, lng, acc)
            }
            GroupComposerRow(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        viewModel.send(draft)
                        draft = ""
                    }
                },
                onShareLocation = shareLocation,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun MemberListDialog(
    members: List<GroupConversationViewModel.MemberView>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${members.size} members") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (members.isEmpty()) {
                    Text(
                        "No members yet — waiting for membership certs to sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (m in members) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val mark = m.pub.bytes
                            .joinToString("") { "%02x".format(it) }
                            .take(2)
                            .uppercase()
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(6.dp),
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(6.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                mark,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            val name = m.displayName
                            val labelMain = when {
                                m.isSelf -> "You"
                                name != null -> name
                                else -> m.pub.bytes
                                    .joinToString("") { "%02x".format(it) }
                                    .take(8) + "⋯"
                            }
                            Text(
                                labelMain,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (m.isCreator) "creator" else "member",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RenameGroupDialog(
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
private fun GroupComposerRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onShareLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onShareLocation) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = "Share my location",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message the group…") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            shape = RoundedCornerShape(20.dp),
        )
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSend()
            },
            enabled = draft.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun EmptyGroupThread() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                "—◇—",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "Group thread. Messages here are signed by each sender " +
                    "and encrypted to every active member.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    msg: GroupMessageEntity,
    fromSelf: Boolean,
    senderDisplayName: String?,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!fromSelf) {
            val mark = msg.fromPub.joinToString("") { "%02x".format(it) }.take(2).uppercase()
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, end = 6.dp)
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(6.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mark,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Surface(
            color = if (fromSelf) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (fromSelf) 14.dp else 4.dp,
                bottomEnd = if (fromSelf) 4.dp else 14.dp,
            ),
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .combinedClickable(
                    onClick = { /* read-only */ },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        ) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboard.setText(AnnotatedString(msg.body))
                        menuOpen = false
                    },
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!fromSelf) {
                    val label = senderDisplayName ?: run {
                        msg.fromPub.joinToString("") { "%02x".format(it) }.take(8) + "⋯"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val loc = LocationPayload.decode(msg.body)
                if (loc != null) {
                    LocationCard(
                        lat = loc.lat,
                        lng = loc.lng,
                        accuracyMeters = loc.accuracyMeters,
                        fromSelf = fromSelf,
                    )
                } else {
                    Text(
                        msg.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(msg.createdAt * 1000)),
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
