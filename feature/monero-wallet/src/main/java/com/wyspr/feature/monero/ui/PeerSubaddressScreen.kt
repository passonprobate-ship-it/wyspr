package com.wyspr.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wyspr.core.ui.components.WysprPanel
import com.wyspr.feature.monero.atomicUnitsAsXmr

/**
 * Per-peer subaddress view. Lists every paired peer that's been
 * issued a relationship-scoped Monero subaddress (via Sprint W4's
 * `peer_subaddress_mint` table) alongside:
 *   - the user-supplied display name (if set)
 *   - the subaddress string itself (long-press to copy via the
 *     row's tap action)
 *   - the (account, sub) index
 *   - total XMR received to that subaddress over its lifetime
 *
 * Useful for "did Alice's payment land?" — the user can verify
 * by checking the row associated with Alice's subaddress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSubaddressScreen(onBack: () -> Unit) {
    val vm: PeerSubaddressViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Per-peer addresses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExplainerPanel()
            if (state.rows.isEmpty()) {
                EmptyPanel(loading = state.loading)
            } else {
                ListPanel(rows = state.rows, onCopy = { addr ->
                    clipboard.setText(AnnotatedString(addr))
                })
            }
        }
    }
}

@Composable
private fun ExplainerPanel() {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "How this works",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Each paired peer is given a unique Monero subaddress so on-chain " +
                    "observers can't link payments received from different peers to the " +
                    "same wallet. The mapping below stays on this device — peers only " +
                    "ever learn their own subaddress.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyPanel(loading: Boolean) {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (loading) {
                "Loading addresses…"
            } else {
                "No subaddresses minted yet. They appear after the first sync round with " +
                    "each paired peer — open a conversation with someone you've paired " +
                    "and the address mints automatically."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListPanel(
    rows: List<PeerSubaddressViewModel.Row>,
    onCopy: (String) -> Unit,
) {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { idx, row ->
                PeerRow(row = row, onCopy = onCopy)
                if (idx < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun PeerRow(
    row: PeerSubaddressViewModel.Row,
    onCopy: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = row.displayName?.takeIf { it.isNotBlank() }
                    ?: row.peerPubFingerprint,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${row.receivedAtomic.atomicUnitsAsXmr()} XMR received",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "Account ${row.accountIndex} · sub ${row.subAddressIndex}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.TextButton(
            onClick = { onCopy(row.address) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(
                text = row.address,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
