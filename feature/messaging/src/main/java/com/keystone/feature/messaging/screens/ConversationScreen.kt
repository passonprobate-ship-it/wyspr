package com.keystone.feature.messaging.screens

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
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.ConversationViewModel
import com.keystone.feature.messaging.image.ImageBubble
import com.keystone.feature.messaging.image.ImagePayload
import com.keystone.feature.messaging.location.LocationPayload
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    peer: PublicKey,
    onBack: () -> Unit,
    onViewPeerPage: () -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    LaunchedEffect(peer.bytes.toList()) { viewModel.bind(peer) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var renameDialogOpen by remember { mutableStateOf(false) }

    val currentDisplayName: String? =
        (state as? ConversationViewModel.UiState.Ready)?.displayName

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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { renameDialogOpen = true },
                    ) {
                        val name = currentDisplayName?.takeIf { it.isNotBlank() }
                        if (name != null) {
                            Text(
                                name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                            )
                            Text(
                                peer.fingerprint.toString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        } else {
                            Text(
                                peer.fingerprint.toString(),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                            Text(
                                "Tap to add a name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    IconButton(onClick = onViewPeerPage) {
                        Icon(Icons.Filled.Public, contentDescription = "View peer's web page")
                    }
                    IconButton(onClick = { renameDialogOpen = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename contact")
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
                        // See GroupConversationScreen for the delay rationale —
                        // applies equally here.
                        LaunchedEffect(s.messages.size) {
                            if (s.messages.isNotEmpty()) {
                                kotlinx.coroutines.delay(50)
                                listState.scrollToItem(s.messages.lastIndex)
                            }
                        }
                        if (s.messages.isEmpty()) {
                            EmptyThread()
                        } else {
                            val ownBytes = s.own?.bytes
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            val shareLocation = rememberLocationShareController { lat, lng, acc ->
                viewModel.sendLocation(lat, lng, acc)
            }
            val sharePhoto = rememberPhotoShareController { jpegBytes ->
                viewModel.sendImage(jpegBytes)
            }
            ComposerRow(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        viewModel.send(draft)
                        draft = ""
                    }
                },
                onShareLocation = shareLocation,
                onSharePhoto = sharePhoto,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun ComposerRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onShareLocation: () -> Unit,
    onSharePhoto: () -> Unit,
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
            placeholder = { Text("Type a private message…") },
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
                else com.keystone.core.ui.KeystoneAccent.Verified,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: MessageEntity, fromSelf: Boolean) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val isImage = ImagePayload.isImage(msg.body)
    val isJumboEmoji = !isImage && msg.body.isJumboEmoji()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromSelf) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (fromSelf) 14.dp else 4.dp,
                bottomEnd = if (fromSelf) 4.dp else 14.dp,
            ),
            // Image bubbles wrap to their content so a 280-dp-wide
            // photo doesn't sit inside a 78%-wide rectangle with
            // empty space around it. Text bubbles keep the 78% cap.
            modifier = Modifier
                .then(
                    if (isImage) Modifier.widthIn(max = 300.dp)
                    else Modifier.fillMaxWidth(0.78f),
                )
                .combinedClickable(
                    onClick = { /* no-op — bubble is read-only on tap */ },
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
                    leadingIcon = {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    },
                    onClick = {
                        clipboard.setText(AnnotatedString(msg.body))
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
                val loc = LocationPayload.decode(msg.body)
                when {
                    isImage -> ImageBubble(body = msg.body, cacheKey = msg.id.toList())
                    loc != null -> LocationCard(
                        lat = loc.lat,
                        lng = loc.lng,
                        accuracyMeters = loc.accuracyMeters,
                        fromSelf = fromSelf,
                    )
                    else -> Text(
                        msg.body,
                        style = if (isJumboEmoji) MaterialTheme.typography.displaySmall
                        else MaterialTheme.typography.bodyMedium,
                        color = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.createdAt * 1000)),
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
                                com.keystone.core.ui.KeystoneAccent.Verified
                            else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}
