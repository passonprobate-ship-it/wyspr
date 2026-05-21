package com.keystone.feature.messaging.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.identity.GroupId
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.ConversationListViewModel
import kotlinx.coroutines.delay
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
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    var overflowOpen by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    // Drive the pull-to-refresh indicator from the sync banner state.
    // PullToRefreshContainer's indicator is on when state.isRefreshing,
    // off otherwise. We tie that to "is a sync round actually in flight"
    // and trigger refresh on pullState.onRefresh.
    val syncing = sync is ConversationListViewModel.SyncBanner.Running
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) viewModel.syncNow()
    }
    LaunchedEffect(syncing) {
        if (!syncing) pullState.endRefresh()
    }

    // Auto-dismiss successful sync banners after a short delay so the
    // "Sync complete — sent N, received M" pill doesn't sit forever
    // after a quiet round. Failures stay until the user taps them so
    // the explanation isn't lost.
    LaunchedEffect(sync) {
        if (sync is ConversationListViewModel.SyncBanner.Done) {
            delay(2_500)
            viewModel.dismissSyncBanner()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Messages", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val running = sync is ConversationListViewModel.SyncBanner.Running
                    IconButton(
                        onClick = viewModel::syncNow,
                        enabled = !running,
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = "Sync now")
                        }
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("New group") },
                            leadingIcon = { Icon(Icons.Filled.GroupAdd, contentDescription = null) },
                            onClick = {
                                overflowOpen = false
                                onCreateGroup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                overflowOpen = false
                                onOpenSettings()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SyncBannerView(banner = sync, onDismiss = viewModel::dismissSyncBanner)

                when (val s = state) {
                    ConversationListViewModel.UiState.Loading -> LoadingPanel()
                    ConversationListViewModel.UiState.NoPeers -> EmptyPanel()
                    is ConversationListViewModel.UiState.Ready -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
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
                                    key = { it.groupId.bytes.toList() },
                                ) { row ->
                                    GroupRowView(row = row, onClick = { onOpenGroup(row.groupId) })
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
                                items(s.rows, key = { it.peer.bytes.toList() }) { row ->
                                    ThreadRowView(row = row, onClick = { onOpenThread(row.peer) })
                                }
                            }
                        }
                    }
                }
            }
            PullToRefreshContainer(
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
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
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Looking for paired peers…",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is ConversationListViewModel.SyncBanner.Done -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
            ) {
                Text(
                    "Synced — sent ${banner.pushed}, received ${banner.received}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        is ConversationListViewModel.SyncBanner.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
            ) {
                Text(
                    banner.message,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
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
private fun EmptyPanel() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
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
                "No paired peers yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Complete an onboarding handshake first. Keystone messages " +
                    "only flow between two devices that have shaken hands in " +
                    "person.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun GroupRowView(
    row: ConversationListViewModel.GroupRow,
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
