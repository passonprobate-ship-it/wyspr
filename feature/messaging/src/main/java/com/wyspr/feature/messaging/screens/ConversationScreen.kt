package com.wyspr.feature.messaging.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import com.wyspr.core.database.entities.MessageEntity
import com.wyspr.core.database.entities.ReactionEntity
import com.wyspr.core.identity.PeerKey
import com.wyspr.core.identity.PublicKey
import com.wyspr.feature.messaging.ConversationViewModel
import com.wyspr.feature.messaging.audio.AudioBubble
import com.wyspr.feature.messaging.audio.AudioPayload
import com.wyspr.feature.messaging.disappear.DisappearPayload
import com.wyspr.feature.messaging.file.FileBubble
import com.wyspr.feature.messaging.file.FilePayload
import com.wyspr.feature.messaging.links.LinkDetector
import com.wyspr.feature.messaging.links.LinkPreviewCard
import com.wyspr.feature.messaging.image.ImageBubble
import com.wyspr.feature.messaging.image.ImagePayload
import com.wyspr.feature.messaging.reactions.ReactionPayload
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clip
import com.wyspr.feature.messaging.location.LocationPayload
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val bubbleTimeFormatter: ThreadLocal<DateFormat> =
    ThreadLocal.withInitial { DateFormat.getTimeInstance(DateFormat.SHORT) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    peer: PublicKey,
    onBack: () -> Unit,
    onViewPeerPage: () -> Unit = {},
    /** Sprint W2: NavHost routes to the SendXmr screen in :feature:monero-wallet. */
    onSendXmr: () -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    LaunchedEffect(peer.bytes.contentHashCode()) { viewModel.bind(peer) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Draft + rename modal state live on the VM so they survive
    // configuration changes and navigation away (e.g., tap-to-rename
    // from the title bar).
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val renameDialogOpen by viewModel.renameOpen.collectAsStateWithLifecycle()

    val currentDisplayName: String? =
        (state as? ConversationViewModel.UiState.Ready)?.displayName

    if (renameDialogOpen) {
        RenameContactDialog(
            initial = currentDisplayName.orEmpty(),
            onDismiss = viewModel::closeRename,
            onConfirm = { newName ->
                viewModel.renameContact(newName)
                viewModel.closeRename()
            },
        )
    }

    val peerDetailsOpen by viewModel.peerDetailsOpen.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    // Name only (or compact fingerprint if no name set).
                    // Tap the title → peer-details sheet with full
                    // fingerprint, rename, and "view web page" actions.
                    // Title bar stays uncluttered; details are a tap away.
                    val name = currentDisplayName?.takeIf { it.isNotBlank() }
                        ?: shortFingerprint(peer.fingerprint.toString())
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        modifier = Modifier.clickable { viewModel.openPeerDetails() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val disappearAfter = (state as? ConversationViewModel.UiState.Ready)?.disappearAfter
                    if (disappearAfter != null) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = "Disappearing messages on",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { viewModel.openDisappearPicker() },
                        )
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (val s = state) {
                    ConversationViewModel.UiState.Loading -> LoadingPanel()
                    is ConversationViewModel.UiState.Ready -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(s.messages.size) {
                            if (s.messages.isNotEmpty()) {
                                val nearBottom = listState.firstVisibleItemIndex >= s.messages.size - 5 ||
                                    listState.layoutInfo.totalItemsCount == 0
                                if (nearBottom) {
                                    kotlinx.coroutines.delay(50)
                                    listState.animateScrollToItem(s.messages.lastIndex)
                                }
                            }
                        }
                        if (s.messages.isEmpty()) {
                            EmptyThread()
                        } else {
                            val ownBytes = s.own?.bytes
                            // Build a lookup once per emission so each
                            // bubble can resolve the message it's replying
                            // to in O(1). Hoisted OUT of LazyColumn because
                            // LazyListScope content isn't a Composable scope.
                            val byId = remember(s.messages) {
                                s.messages.associateBy {
                                    com.wyspr.core.identity.PeerKey(it.id)
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                items(s.messages, key = { it.id.toList() }) { msg ->
                                    if (ReactionPayload.isReaction(msg.body)) return@items
                                    if (DisappearPayload.isDisappear(msg.body)) return@items
                                    val decoded = com.wyspr.feature.messaging.reply.ReplyPayload.decode(msg.body)
                                    val quoted = decoded?.replyToId?.let { id ->
                                        byId[PeerKey(id)]
                                    }
                                    val ownBytesNN = ownBytes
                                    val msgReactions = s.reactions[PeerKey(msg.id)].orEmpty()
                                    MessageBubble(
                                        msg = msg,
                                        fromSelf = ownBytesNN?.contentEquals(msg.fromPub) == true,
                                        quoted = quoted,
                                        quotedFromSelf = quoted != null
                                            && ownBytesNN != null
                                            && quoted.fromPub.contentEquals(ownBytesNN),
                                        reactions = msgReactions,
                                        ownPub = ownBytesNN,
                                        onReply = { viewModel.pickReply(msg) },
                                        onReact = { emoji -> viewModel.sendReaction(msg, emoji) },
                                        onRetractReaction = { viewModel.retractReaction(msg) },
                                        onScrollToQuoted = { id ->
                                            val idx = s.messages.indexOfFirst { it.id.contentEquals(id) }
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
            val shareFile = rememberFileShareController { name, mime, bytes ->
                viewModel.sendFile(name, mime, bytes)
            }
            val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
            replyingTo?.let { target ->
                val ownBytes = (state as? ConversationViewModel.UiState.Ready)?.own?.bytes
                ReplyComposerCard(
                    target = target,
                    targetFromSelf = ownBytes?.contentEquals(target.fromPub) == true,
                    onCancel = viewModel::cancelReply,
                )
            }
            ComposerRow(
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

    if (peerDetailsOpen) {
        PeerDetailsSheet(
            peer = peer,
            displayName = currentDisplayName,
            onDismiss = viewModel::closePeerDetails,
            onRename = viewModel::openRename,
            onNotes = viewModel::openNotes,
            onDisappearTimer = viewModel::openDisappearPicker,
            onViewPage = {
                viewModel.closePeerDetails()
                onViewPeerPage()
            },
            onSendXmr = {
                viewModel.closePeerDetails()
                onSendXmr()
            },
        )
    }
    val notesOpen by viewModel.notesOpen.collectAsStateWithLifecycle()
    val currentNotes = (state as? ConversationViewModel.UiState.Ready)?.notes
    if (notesOpen) {
        EditNotesDialog(
            initial = currentNotes.orEmpty(),
            displayName = currentDisplayName ?: peer.fingerprint.toString().take(20),
            onDismiss = viewModel::closeNotes,
            onConfirm = { value ->
                viewModel.saveNotes(value)
                viewModel.closeNotes()
            },
        )
    }
    val disappearPickerOpen by viewModel.disappearPickerOpen.collectAsStateWithLifecycle()
    val currentDisappearAfter = (state as? ConversationViewModel.UiState.Ready)?.disappearAfter
    if (disappearPickerOpen) {
        DisappearTimerDialog(
            current = currentDisappearAfter,
            onDismiss = viewModel::closeDisappearPicker,
            onConfirm = { seconds ->
                viewModel.setDisappearTimer(seconds)
                viewModel.closeDisappearPicker()
            },
        )
    }
}

/**
 * Bottom sheet that opens when the user taps the conversation title.
 * Holds everything that used to clutter the top bar — full
 * fingerprint, rename, notes, and "view web page" action.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PeerDetailsSheet(
    peer: PublicKey,
    displayName: String?,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onNotes: () -> Unit,
    onDisappearTimer: () -> Unit,
    onViewPage: () -> Unit,
    onSendXmr: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                displayName?.takeIf { it.isNotBlank() } ?: "Unnamed peer",
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
            Text(
                peer.fingerprint.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(20.dp))
            androidx.compose.material3.TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                Text("Rename contact", modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.material3.TextButton(onClick = onNotes, modifier = Modifier.fillMaxWidth()) {
                Text("Notes", modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.material3.TextButton(onClick = onDisappearTimer, modifier = Modifier.fillMaxWidth()) {
                Text("Disappearing messages", modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.material3.TextButton(onClick = onViewPage, modifier = Modifier.fillMaxWidth()) {
                Text("View web page", modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.material3.TextButton(onClick = onSendXmr, modifier = Modifier.fillMaxWidth()) {
                Text("Send XMR", modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Multi-line free-form notes editor. Notes are local-only and never
 * synced over the wire. Saved on tap of "Save"; cancel discards.
 */
@Composable
private fun EditNotesDialog(
    initial: String,
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes about $displayName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Private to this device — never shared with the peer or anyone else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text("Where we met, what they care about, anything you want to remember…") },
                    singleLine = false,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Compact 4-then-4 fingerprint: `abcd⋯wxyz`. Used as a fallback
 *  title when no friendly name is set. */
private fun shortFingerprint(fp: String): String {
    val raw = fp.filter { it.isLetterOrDigit() }
    if (raw.length < 10) return fp
    return raw.take(4) + "⋯" + raw.takeLast(4)
}

@Composable
private fun DisappearTimerDialog(
    current: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disappearing messages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "New messages will be deleted from both devices after the chosen time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                for ((label, seconds) in DisappearPayload.TIMER_OPTIONS) {
                    val selected = current == seconds
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(seconds) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ComposerRow(
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onSharePhoto) {
            Icon(
                Icons.Filled.Image,
                contentDescription = "Send a photo",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onShareFile) {
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = "Send a file",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onRecordVoice) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Record voice note",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Type a message…") },
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
private fun EmptyThread() {
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
                "Say hello — your first message will land here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Inline card body for messages whose payload is a location.
 * Tapping fires a geo: intent so the user's maps app takes over.
 * Falls back gracefully if no app handles the intent (Android shows
 * its own chooser dialog or nothing — either is acceptable, location
 * rendering doesn't need to assume Maps is installed).
 */
@Composable
internal fun LocationCard(
    lat: Double,
    lng: Double,
    accuracyMeters: Float,
    fromSelf: Boolean,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .clickable {
                val uri = "geo:%.6f,%.6f?q=%.6f,%.6f(Shared%%20Location)"
                    .format(lat, lng, lat, lng)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                else com.wyspr.core.ui.WysprAccent.Verified,
            )
            Text(
                "Location",
                style = MaterialTheme.typography.labelLarge,
                color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "%.5f, %.5f".format(lat, lng),
            style = MaterialTheme.typography.bodyMedium,
            color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
        val acc = accuracyMeters.toInt()
        if (acc > 0) {
            Text(
                "± $acc m · tap to open in maps",
                style = MaterialTheme.typography.labelSmall,
                color = (if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f),
            )
        } else {
            Text(
                "tap to open in maps",
                style = MaterialTheme.typography.labelSmall,
                color = (if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Map MessageEntity.status → (icon, contentDescription). Self-only;
 * inbound messages never carry this row.
 */
private data class StatusGlyph(val icon: ImageVector, val description: String)

private fun statusFor(status: String): StatusGlyph = when (status) {
    "pending" -> StatusGlyph(Icons.Filled.Schedule, "Queued")
    "sent" -> StatusGlyph(Icons.Filled.Check, "Sent")
    "delivered" -> StatusGlyph(Icons.Filled.DoneAll, "Delivered")
    "read" -> StatusGlyph(Icons.Filled.DoneAll, "Read")
    else -> StatusGlyph(Icons.Filled.Done, status)
}

private val QUICK_REACT_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: MessageEntity,
    fromSelf: Boolean,
    quoted: MessageEntity? = null,
    quotedFromSelf: Boolean = false,
    reactions: List<ReactionEntity> = emptyList(),
    ownPub: ByteArray? = null,
    onReply: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onRetractReaction: () -> Unit = {},
    onScrollToQuoted: (ByteArray) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val displayBody = remember(msg.body) {
        com.wyspr.feature.messaging.reply.ReplyPayload.decode(msg.body)?.body ?: msg.body
    }
    val isImage = ImagePayload.isImage(displayBody)
    val isJumboEmoji = !isImage && displayBody.isJumboEmoji()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
        ) {
            Box {
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
                            else Modifier.fillMaxWidth(0.78f),
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = if (isImage) 4.dp else 14.dp,
                            vertical = if (isImage) 4.dp else 10.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (quoted != null) {
                            QuotedSnippet(
                                quoted = quoted,
                                quotedFromSelf = quotedFromSelf,
                                onTap = { onScrollToQuoted(quoted.id) },
                                bubbleFromSelf = fromSelf,
                            )
                        }
                        val loc = LocationPayload.decode(displayBody)
                        val isAudio = AudioPayload.isAudio(displayBody)
                        val isFilePl = FilePayload.isFile(displayBody)
                        when {
                            isImage -> ImageBubble(body = displayBody, cacheKey = msg.id.contentHashCode())
                            isAudio -> AudioBubble(body = displayBody, fromSelf = fromSelf, cacheKey = msg.id.contentHashCode())
                            isFilePl -> FileBubble(body = displayBody, fromSelf = fromSelf)
                            loc != null -> LocationCard(
                                lat = loc.lat,
                                lng = loc.lng,
                                accuracyMeters = loc.accuracyMeters,
                                fromSelf = fromSelf,
                            )
                            else -> {
                                Text(
                                    displayBody,
                                    style = if (isJumboEmoji) MaterialTheme.typography.displaySmall
                                    else MaterialTheme.typography.bodyMedium,
                                    color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                val links = remember(displayBody) { LinkDetector.findUrls(displayBody) }
                                if (links.isNotEmpty() && !isJumboEmoji) {
                                    LinkPreviewCard(url = links.first().url, fromSelf = fromSelf)
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            if (msg.expiresAt != null) {
                                Icon(
                                    Icons.Filled.Timer,
                                    contentDescription = "Disappearing",
                                    modifier = Modifier.size(12.dp),
                                    tint = (if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.6f),
                                )
                            }
                            Text(
                                bubbleTimeFormatter.get()!!.format(Date(msg.createdAt * 1000)),
                                style = MaterialTheme.typography.labelSmall,
                                color = (if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                    .copy(alpha = 0.75f),
                            )
                            if (fromSelf) {
                                val glyph = statusFor(msg.status)
                                Icon(
                                    glyph.icon,
                                    contentDescription = glyph.description,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (msg.status == "read")
                                        com.wyspr.core.ui.WysprAccent.Verified
                                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (emoji in QUICK_REACT_EMOJI) {
                            Text(
                                emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clickable {
                                        menuOpen = false
                                        onReact(emoji)
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onReply()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            clipboard.setText(AnnotatedString(displayBody))
                            menuOpen = false
                        },
                    )
                }
            }
        }

        if (reactions.isNotEmpty()) {
            val grouped = remember(reactions) {
                reactions.groupBy { it.emoji }
                    .map { (emoji, list) -> emoji to list }
                    .sortedByDescending { it.second.size }
            }
            Row(
                modifier = Modifier
                    .then(
                        if (fromSelf) Modifier.fillMaxWidth().wrapContentWidth(Alignment.End)
                        else Modifier,
                    )
                    .padding(start = if (fromSelf) 0.dp else 8.dp, end = if (fromSelf) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((emoji, senders) in grouped) {
                    val isMine = ownPub != null && senders.any { it.fromPub.contentEquals(ownPub) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .then(
                                if (isMine) Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp),
                                ) else Modifier,
                            )
                            .clickable {
                                if (isMine) onRetractReaction() else onReact(emoji)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(emoji, fontSize = 14.sp)
                            if (senders.size > 1) {
                                Text(
                                    "${senders.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quoted-snippet pill rendered at the top of a reply bubble. WhatsApp's
 * signature pattern: an accent bar on the left, a small sender label,
 * and a single-line preview of the quoted message. Tapping scrolls the
 * list to the original message.
 */
@Composable
private fun QuotedSnippet(
    quoted: MessageEntity,
    quotedFromSelf: Boolean,
    onTap: () -> Unit,
    bubbleFromSelf: Boolean,
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
            Text(
                if (quotedFromSelf) "You" else "Reply",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                quotedPreview(quoted.body),
                style = MaterialTheme.typography.bodySmall,
                color = if (bubbleFromSelf) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** Short single-line preview of [body] for the quote pill. Strips
 *  reply markers (nested replies just show the inner text) and
 *  substitutes friendly labels for tagged-body markers. */
private fun quotedPreview(body: String): String {
    val unwrapped = com.wyspr.feature.messaging.reply.ReplyPayload.decode(body)?.body ?: body
    return when {
        unwrapped.startsWith("wyspr:loc:") -> "📍 Location"
        ImagePayload.isImage(unwrapped) -> "📷 Photo"
        AudioPayload.isAudio(unwrapped) -> "🎙 Voice note"
        FilePayload.isFile(unwrapped) -> "📎 File"
        ReactionPayload.isReaction(unwrapped) -> "Reacted"
        DisappearPayload.isDisappear(unwrapped) -> "⏱ Timer changed"
        else -> unwrapped.take(80)
    }
}

/**
 * "Replying to X" card pinned above the composer. Shown while the
 * user has a reply target selected. The X button cancels the
 * reply and returns the composer to plain mode.
 */
@Composable
private fun ReplyComposerCard(
    target: MessageEntity,
    targetFromSelf: Boolean,
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
                if (targetFromSelf) "Replying to yourself" else "Replying",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                quotedPreview(target.body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel reply")
        }
    }
}
