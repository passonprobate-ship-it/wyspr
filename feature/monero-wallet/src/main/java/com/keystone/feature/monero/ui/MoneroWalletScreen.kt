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
            is WalletState.Ready -> ReadyPanel(state)
            is WalletState.Failed -> FailedPanel(state.message, onRetry)
        }

        // Restore action — always visible at the bottom so it's
        // reachable even if a fresh wallet is in a Failed state.
        WalletActionsPanel(onRestoreWallet = onRestoreWallet)
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
private fun WalletActionsPanel(onRestoreWallet: () -> Unit) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Restore a different wallet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Have a Monero seed from another wallet? Restore it here. This " +
                    "replaces the current on-device wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onRestoreWallet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore from 25-word seed")
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
private fun ReadyPanel(state: WalletState.Ready) {
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
        }
    }
    TxHistoryPanel(state.transactions)
}

@Composable
private fun TxHistoryPanel(txs: List<TxHistoryEntry>) {
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
                    TxHistoryRow(tx)
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
private fun TxHistoryRow(tx: TxHistoryEntry) {
    val abs = kotlin.math.abs(tx.amountAtomicUnits).atomicUnitsAsXmr()
    val sign = if (tx.isInbound) "+" else "−"
    val color = if (tx.isInbound) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.tertiary
    val ts = tx.blockTimestamp?.let { TX_TS_FORMAT.format(it) } ?: "pending"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$sign $abs XMR",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            Text(
                text = ts,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
