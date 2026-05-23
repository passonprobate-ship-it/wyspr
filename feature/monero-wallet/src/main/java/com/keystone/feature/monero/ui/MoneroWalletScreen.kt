package com.keystone.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.MoneroWalletService.TxHistoryEntry
import com.keystone.feature.monero.MoneroWalletService.WalletState
import com.keystone.feature.monero.atomicUnitsAsXmr
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * Sprint W1 Monero wallet screen.
 *
 * Renders one of four states from [WalletState]:
 *  - Idle: brief placeholder before bootstrap fires.
 *  - Binding: connecting to mollyim's in-process wallet service.
 *  - Ready(balance, address, ...): the working wallet — balance,
 *    receive address, tx count.
 *  - Failed: error message with a Retry button.
 *
 * Send + tx history + restore-from-seed land in Sprints W2 / W3.
 */
@Composable
fun MoneroWalletScreen(
    state: WalletState,
    seedBackupAcknowledged: Boolean,
    onRetry: () -> Unit,
    onRevealSeed: () -> Unit,
    onRestoreWallet: () -> Unit,
    onSweepWallet: () -> Unit = {},
    onOpenTx: (String) -> Unit = {},
    onShowReceive: () -> Unit = {},
    onShowPeerSubaddresses: () -> Unit = {},
    onLearnAboutXmr: () -> Unit = {},
    onSendToAddress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Monero",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "All wallet traffic routes through Keystone's embedded Tor proxy. " +
                "The remote node operator sees a Tor exit, never your IP.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!seedBackupAcknowledged && state is WalletState.Ready) {
            SeedBackupBanner(onRevealSeed = onRevealSeed)
        }

        when (state) {
            WalletState.Idle -> IdlePanel()
            WalletState.Binding -> BindingPanel()
            is WalletState.Ready -> ReadyPanel(
                state = state,
                onOpenTx = onOpenTx,
                onShowReceive = onShowReceive,
                onShowPeerSubaddresses = onShowPeerSubaddresses,
                onLearnAboutXmr = onLearnAboutXmr,
            )
            is WalletState.Failed -> FailedPanel(state.message, onRetry)
        }

        // Restore + sweep actions — always visible at the bottom so
        // they're reachable even if a fresh wallet is in a Failed
        // state (the user may want to wipe & restore in that case).
        WalletActionsPanel(
            onRestoreWallet = onRestoreWallet,
            onSweepWallet = onSweepWallet,
            onSendToAddress = onSendToAddress,
        )
    }
}

@Composable
private fun SeedBackupBanner(onRevealSeed: () -> Unit) {
    KeystonePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.tertiary,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Back up your seed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "If you lose this device without writing down your 25-word seed, " +
                    "the funds in this wallet are gone. Tap below to reveal them once " +
                    "(behind biometric) and write them on paper.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRevealSeed, modifier = Modifier.fillMaxWidth()) {
                Text("Reveal my seed words")
            }
        }
    }
}

@Composable
private fun WalletActionsPanel(
    onRestoreWallet: () -> Unit,
    onSweepWallet: () -> Unit,
    onSendToAddress: () -> Unit,
) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Wallet actions",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Send to a typed address (with optional address-book saving), " +
                    "restore a different wallet, or sweep this one into a single " +
                    "destination address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onSendToAddress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send to address")
            }
            androidx.compose.material3.OutlinedButton(
                onClick = onRestoreWallet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore from 25-word seed")
            }
            androidx.compose.material3.OutlinedButton(
                onClick = onSweepWallet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sweep to another address")
            }
        }
    }
}

@Composable
private fun IdlePanel() {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Starting wallet…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BindingPanel() {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Connecting to wallet service",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "First-time setup generates a new wallet seed. This is fast — " +
                    "block sync happens in the background once Tor is up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadyPanel(
    state: WalletState.Ready,
    onOpenTx: (String) -> Unit,
    onShowReceive: () -> Unit,
    onShowPeerSubaddresses: () -> Unit,
    onLearnAboutXmr: () -> Unit,
) {
    val balanceIsZero = state.balanceAtomicUnits == 0L && state.pendingAtomicUnits == 0L
    if (balanceIsZero) {
        NewUserNudge(onLearnAboutXmr = onLearnAboutXmr)
    }
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Balance",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${state.balanceAtomicUnits.atomicUnitsAsXmr()} XMR",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state.pendingAtomicUnits > 0L) {
                Text(
                    text = "${state.pendingAtomicUnits.atomicUnitsAsXmr()} XMR pending",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            KeyValue("Confirmed", "${state.confirmedAtomicUnits.atomicUnitsAsXmr()} XMR")
            KeyValue("Transactions seen", "${state.txCount}")
            if (state.lastCheckedHeight > 0) {
                KeyValue("Synced through block", "${state.lastCheckedHeight}")
            } else {
                KeyValue("Sync status", "Connecting to node…")
            }
        }
    }
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Your primary receive address",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.primaryAddress,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = "Paired peers see a unique subaddress per relationship via auto-" +
                    "exchange — this primary view is for out-of-band sharing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onShowReceive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Show QR")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = onShowPeerSubaddresses,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Per-peer addresses")
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onLearnAboutXmr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Learn about Monero")
            }
        }
    }
    TxHistoryPanel(state.transactions, onOpenTx)
}

@Composable
private fun NewUserNudge(onLearnAboutXmr: () -> Unit) {
    KeystonePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.tertiary,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "New here?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "Your wallet is ready but empty. Tap below for a quick explainer of " +
                    "why Keystone uses Monero, what privacy guarantees you're actually " +
                    "getting, and where to acquire XMR — including no-KYC options.",
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.Button(
                onClick = onLearnAboutXmr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Learn about Monero")
            }
        }
    }
}

@Composable
private fun TxHistoryPanel(txs: List<TxHistoryEntry>, onOpenTx: (String) -> Unit) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Transactions",
                style = MaterialTheme.typography.titleMedium,
            )
            if (txs.isEmpty()) {
                Text(
                    text = "Nothing yet. Once a sync round confirms an inbound or you " +
                        "Send XMR, entries land here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Render up to the first 50 to keep recompose under
                // budget on cold wallets with deep history. A "show
                // all" expander is future polish.
                val visible = txs.take(50)
                visible.forEachIndexed { index, tx ->
                    TxHistoryRow(tx, onOpenTx)
                    if (index < visible.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                if (txs.size > visible.size) {
                    Text(
                        text = "+${txs.size - visible.size} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val TX_TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun TxHistoryRow(tx: TxHistoryEntry, onOpenTx: (String) -> Unit) {
    val abs = kotlin.math.abs(tx.amountAtomicUnits).atomicUnitsAsXmr()
    val sign = if (tx.isInbound) "+" else "−"
    val color = if (tx.isInbound) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.tertiary
    val ts = tx.blockTimestamp?.let { TX_TS_FORMAT.format(it) } ?: "pending"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTx(tx.txHash) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$sign $abs XMR",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            if (tx.isUnconfirmed) {
                PendingPill()
            } else {
                Text(
                    text = ts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = tx.txHash.take(20) + "…",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!tx.isInbound && tx.feeAtomicUnits > 0L) {
            Text(
                text = "fee ${tx.feeAtomicUnits.atomicUnitsAsXmr()} XMR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingPill() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "PENDING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun FailedPanel(message: String, onRetry: () -> Unit) {
    KeystonePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.error,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Wallet bring-up failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
