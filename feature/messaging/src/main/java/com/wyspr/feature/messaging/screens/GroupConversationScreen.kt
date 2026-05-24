package com.wyspr.feature.messaging.screens

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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.wyspr.core.database.entities.GroupMessageEntity
import com.wyspr.core.identity.GroupId
import com.wyspr.feature.messaging.GroupConversationViewModel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import com.wyspr.feature.messaging.location.LocationPayload
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupConversationScreen(
    groupId: GroupId,
    onBack: () -> Unit,
    viewModel: GroupConversationViewModel = hiltViewModel(),
) {
    LaunchedEffect(groupId.bytes.contentHashCode()) { viewModel.bind(groupId) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Draft + modal state on the VM so they survive configuration
    // changes and navigation away.
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val memberDialogOpen by viewModel.memberListOpen.collectAsStateWithLifecycle()
    val renameDialogOpen by viewModel.renameOpen.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<List<GroupConversationViewModel.MemberView>>(emptyList()) }

    LaunchedEffect(memberDialogOpen) {
        if (memberDialogOpen) members = viewModel.loadMembers()
    }

    if (memberDialogOpen) {
        MemberListDialog(
            members = members,
            onDismiss = viewModel::closeMemberList,
        )
    }
    if (renameDialogOpen) {
        val current = (state as? GroupConversationViewModel.UiState.Ready)?.groupName.orEmpty()
        RenameGroupDialog(
            initial = current,
            onDismiss = viewModel::closeRename,
            onConfirm = { newName ->
                viewModel.renameLocal(newName)
                viewModel.closeRename()
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val s = state as? GroupConversationViewModel.UiState.Ready
                    // Single-line group name. Tap the name → member
                    // list (group "details" sheet). Rename moves to an
                    // overflow action in the sheet rather than its
                    // own top-bar button.
                    Text(
                        s?.groupName ?: "Group",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        modifier = Modifier.clickable { viewModel.openMemberList() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openRename) {
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
                        val reversed = remember(s.messages) { s.messages.asReversed() }
                        LaunchedEffect(s.messages.size) {
                            if (s.messages.isNotEmpty()) {
                                kotlinx.coroutines.delay(50)
                                listState.animateScrollToItem(0)
                            }
                        }
                        if (s.messages.isEmpty()) {
                            EmptyGroupThread()
                        } else {
                            val ownBytes = s.ownPub?.bytes
                            val byId = remember(s.messages) {
                                s.messages.associateBy {
                                    com.wyspr.core.identity.PeerKey(it.id)
                                }
                            }
                            LazyColumn(
                                state = listState,
                                reverseLayout = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                items(reversed, key = { it.id.toList() }) { msg ->
                                    val decoded = com.wyspr.feature.messaging.reply.ReplyPayload.decode(msg.body)
                                    val quoted = decoded?.replyToId?.let { id ->
                                        byId[com.wyspr.core.identity.PeerKey(id)]
                                    }
                                    GroupMessageBubble(
                                        msg = msg,
                                        fromSelf = ownBytes?.contentEquals(msg.fromPub) == true,
                                        senderDisplayName = s.displayNames[com.wyspr.core.identity.PeerKey(msg.fromPub)],
                                        quoted = quoted,
                                        quotedSenderName = quoted?.let { q ->
                                            s.displayNames[com.wyspr.core.identity.PeerKey(q.fromPub)]
                                        },
                                        quotedFromSelf = quoted != null && ownBytes != null &&
                                            quoted.fromPub.contentEquals(ownBytes),
                                        onReply = { viewModel.pickReply(msg) },
                                        onScrollToQuoted = { id ->
                                            val idx = reversed.indexOfFirst { it.id.contentEquals(id) }
                                            if (idx >= 0) {
                                                scope.launch { listState.animateScrollToItem(idx) }
                                            }
                                        },
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
            val sharePhoto = rememberPhotoShareController { jpegBytes ->
                viewModel.sendImage(jpegBytes)
            }
            val recordVoice = rememberVoiceRecordController { audioBytes, durationMs ->
                viewModel.sendVoiceNote(audioBytes, durationMs)
            }
            val shareFile = rememberFileShareController { fileName, mimeType, fileBytes ->
                viewModel.sendFile(fileName, mimeType, fileBytes)
            }
            val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
            replyingTo?.let { target ->
                val ownB = (state as? GroupConversationViewModel.UiState.Ready)?.ownPub?.bytes
                val senderName = (state as? GroupConversationViewModel.UiState.Ready)
                    ?.displayNames?.get(com.wyspr.core.identity.PeerKey(target.fromPub))
                GroupReplyComposerCard(
                    target = target,
                    senderLabel = when {
                        ownB?.contentEquals(target.fromPub) == true -> "Replying to yourself"
                        senderName != null -> "Replying to $senderName"
                        else -> "Replying"
                    },
                    onCancel = viewModel::cancelReply,
                )
            }
            GroupComposerRow(
                draft = draft,
                onDraftChange = viewModel::updateDraft,
                onSend = {
                    if (draft.isNotBlank()) {
                        viewModel.send(draft)
                    }
                },
                onShareLocation = shareLocation,
                onSharePhoto = sharePhoto,
                onRecordVoice = recordVoice,
                onShareFile = shareFile,
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
    onSharePhoto: () -> Unit,
    onRecordVoice: () -> Unit,
    onShareFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var toolsExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.animation.AnimatedVisibility(visible = toolsExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GroupAttachmentChip(Icons.Filled.Image, "Photo") {
                    toolsExpanded = false
                    onSharePhoto()
                }
                GroupAttachmentChip(Icons.Filled.AttachFile, "File") {
                    toolsExpanded = false
                    onShareFile()
                }
                GroupAttachmentChip(Icons.Filled.LocationOn, "Location") {
                    toolsExpanded = false
                    onShareLocation()
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { toolsExpanded = !toolsExpanded }) {
                Icon(
                    if (toolsExpanded) Icons.Filled.Close else Icons.Filled.Add,
                    contentDescription = if (toolsExpanded) "Close tools" else "Attach",
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
            IconButton(onClick = onRecordVoice) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Voice note",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
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
}

@Composable
private fun GroupAttachmentChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
            )
        },
    )
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
    quoted: GroupMessageEntity? = null,
    quotedSenderName: String? = null,
    quotedFromSelf: Boolean = false,
    onReply: () -> Unit = {},
    onScrollToQuoted: (ByteArray) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val displayBody = remember(msg.body) {
        com.wyspr.feature.messaging.reply.ReplyPayload.decode(msg.body)?.body ?: msg.body
    }
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
        val isImage = com.wyspr.feature.messaging.image.ImagePayload.isImage(displayBody)
        val isJumboEmoji = !isImage && displayBody.isJumboEmoji()
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
                .then(
                    if (isImage) Modifier.widthIn(max = 300.dp)
                    else Modifier.fillMaxWidth(0.74f),
                )
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
                    text = { Text("Reply") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onReply()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboard.setText(AnnotatedString(displayBody))
                        menuOpen = false
                    },
                )
            }
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isImage) 4.dp else 14.dp,
                    vertical = if (isImage) 4.dp else 10.dp,
                ),
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
                if (quoted != null) {
                    GroupQuotedSnippet(
                        quoted = quoted,
                        quotedSenderName = quotedSenderName,
                        quotedFromSelf = quotedFromSelf,
                        bubbleFromSelf = fromSelf,
                        onTap = { onScrollToQuoted(quoted.id) },
                    )
                }
                val loc = LocationPayload.decode(displayBody)
                when {
                    isImage -> com.wyspr.feature.messaging.image.ImageBubble(
                        body = displayBody,
                        cacheKey = msg.id.contentHashCode(),
                    )
                    loc != null -> LocationCard(
                        lat = loc.lat,
                        lng = loc.lng,
                        accuracyMeters = loc.accuracyMeters,
                        fromSelf = fromSelf,
                    )
                    else -> Text(
                        displayBody,
                        style = if (isJumboEmoji) MaterialTheme.typography.displaySmall
                        else MaterialTheme.typography.bodyMedium,
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

@Composable
private fun GroupQuotedSnippet(
    quoted: GroupMessageEntity,
    quotedSenderName: String?,
    quotedFromSelf: Boolean,
    bubbleFromSelf: Boolean,
    onTap: () -> Unit,
) {
    val accent = if (bubbleFromSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.primary
    val container = if (bubbleFromSelf)
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(accent)
                .width(3.dp)
                .heightIn(min = 32.dp),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .weight(1f),
        ) {
            val senderLabel = when {
                quotedFromSelf -> "You"
                quotedSenderName != null -> quotedSenderName
                else -> "Member"
            }
            Text(
                senderLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                groupQuotedPreview(quoted.body),
                style = MaterialTheme.typography.bodySmall,
                color = if (bubbleFromSelf) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

private fun groupQuotedPreview(body: String): String {
    val unwrapped = com.wyspr.feature.messaging.reply.ReplyPayload.decode(body)?.body ?: body
    return when {
        unwrapped.startsWith("wyspr:loc:") -> "📍 Location"
        com.wyspr.feature.messaging.image.ImagePayload.isImage(unwrapped) -> "📷 Photo"
        com.wyspr.feature.messaging.audio.AudioPayload.isAudio(unwrapped) -> "🎙 Voice note"
        com.wyspr.feature.messaging.file.FilePayload.isFile(unwrapped) -> "📎 File"
        com.wyspr.feature.messaging.reactions.ReactionPayload.isReaction(unwrapped) -> "Reacted"
        com.wyspr.feature.messaging.disappear.DisappearPayload.isDisappear(unwrapped) -> "⏱ Timer changed"
        else -> unwrapped.take(80)
    }
}

@Composable
private fun GroupReplyComposerCard(
    target: GroupMessageEntity,
    senderLabel: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary)
                .width(3.dp)
                .heightIn(min = 32.dp),
        )
        Column(
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        ) {
            Text(
                senderLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                groupQuotedPreview(target.body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel reply",
            )
        }
    }
}
