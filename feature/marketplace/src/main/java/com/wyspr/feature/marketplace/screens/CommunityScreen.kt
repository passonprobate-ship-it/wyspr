package com.wyspr.feature.marketplace.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.trust.TrustLevel
import com.wyspr.core.ui.WysprAccent
import com.wyspr.feature.marketplace.CommunityViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Community — flat searchable list of every paired peer with a small
 * trust-level dot next to the name. WhatsApp's contact-list pattern,
 * adapted to Wyspr's trust graph: no more dense trust-tier sections,
 * no nested cards — one row per peer.
 *
 * Self card sits at the top (compact), Tor status as a thin pill,
 * search field, then the list. Trust-graph visualization stays
 * accessible from the top-bar overflow icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    onOpenGraph: () -> Unit = {},
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    val onionAddress by viewModel.onionAddress.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Community", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenGraph) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "View trust graph")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when (val s = state) {
            CommunityViewModel.UiState.Loading -> LoadingPanel(padding)
            CommunityViewModel.UiState.NoCommunity -> EmptyPanel(padding)
            is CommunityViewModel.UiState.Ready -> ReadyList(
                state = s,
                torState = torState,
                onionAddress = onionAddress,
                search = search,
                onSearchChange = { search = it },
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun ReadyList(
    state: CommunityViewModel.UiState.Ready,
    torState: TorBackend.State,
    onionAddress: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val filtered = remember(state.peers, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) state.peers
        else state.peers.filter { peer ->
            peer.fingerprint.toString().lowercase().contains(q) ||
                (peer.displayName?.lowercase()?.contains(q) == true)
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            SelfHeader(state = state, torState = torState, onionAddress = onionAddress)
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                placeholder = { Text("Search by name or fingerprint") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    if (search.isBlank()) "No paired peers yet"
                    else "No peers match \"$search\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
            }
        } else {
            items(filtered, key = { it.fingerprint.toString() }) { peer ->
                PeerRow(peer)
            }
        }
    }
}

/** Compact "you" header — name, fingerprint, trust badge, community ID
 *  short hex (tap to copy), Tor status. One self-contained block. */
@Composable
private fun SelfHeader(
    state: CommunityViewModel.UiState.Ready,
    torState: TorBackend.State,
    onionAddress: String?,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrustDot(state.ownTrustLevel)
                Text(
                    "You — ${state.ownTrustLevel.shortLabel()}",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (state.isFounder) {
                    Text(
                        " · founder",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            Text(
                state.ownFingerprint.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .clickable {
                        clipboard.setText(AnnotatedString(state.communityIdHex))
                        android.widget.Toast.makeText(
                            context,
                            "Community ID copied",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
            ) {
                Text(
                    "Community ${state.communityIdHex.take(6)}…${state.communityIdHex.takeLast(6)} (tap to copy)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            Text(
                torStatusText(torState, onionAddress),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun torStatusText(state: TorBackend.State, onion: String?): String = when (state) {
    TorBackend.State.Idle -> "Tor: starting…"
    is TorBackend.State.Bootstrapping -> "Tor: bootstrapping ${state.percent}%"
    TorBackend.State.Ready -> {
        if (onion != null) "Tor ready · ${onion.take(8)}…${onion.takeLast(6)}.onion"
        else "Tor ready"
    }
    is TorBackend.State.Failed -> "Tor: ${state.message}"
    TorBackend.State.Unavailable -> "Tor: unavailable"
}

@Composable
private fun PeerRow(peer: CommunityViewModel.PeerEntry) {
    val displayName = peer.displayName?.takeIf { it.isNotBlank() }
    val short = peer.fingerprint.toString().let { fp ->
        val raw = fp.filter { it.isLetterOrDigit() }
        if (raw.length < 10) fp
        else raw.take(4) + "⋯" + raw.takeLast(4)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrustDot(peer.trustLevel)
        Spacer(modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                displayName ?: short,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (displayName != null) FontWeight.Normal else FontWeight.SemiBold,
                fontFamily = if (displayName != null) FontFamily.Default else FontFamily.Monospace,
            )
            val sub = buildString {
                append(peer.trustLevel.shortLabel())
                if (peer.peerOnion != null) append(" · Tor")
                append(" · paired ")
                append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(peer.pairedAt * 1000)))
            }
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrustDot(level: TrustLevel) {
    val color: Color = when (level) {
        TrustLevel.Root, TrustLevel.Full -> WysprAccent.Verified
        TrustLevel.Provisional -> WysprAccent.Pending
        TrustLevel.Quarantined -> WysprAccent.Quarantine
        TrustLevel.Unknown -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color = color, shape = CircleShape),
    )
}

private fun TrustLevel.shortLabel(): String = when (this) {
    TrustLevel.Root -> "Root"
    TrustLevel.Full -> "Full"
    TrustLevel.Provisional -> "Provisional"
    TrustLevel.Quarantined -> "Quarantined"
    TrustLevel.Unknown -> "Unknown"
}

@Composable
private fun LoadingPanel(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text("Loading…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyPanel(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No community on this device. Complete an onboarding handshake first.",
            modifier = Modifier.padding(horizontal = 32.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
