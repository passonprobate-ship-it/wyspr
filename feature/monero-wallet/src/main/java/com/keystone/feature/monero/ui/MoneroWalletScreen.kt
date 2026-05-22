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
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.MoneroWalletService.WalletState
import com.keystone.feature.monero.atomicUnitsAsXmr

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
    onRetry: () -> Unit,
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

        when (state) {
            WalletState.Idle -> IdlePanel()
            WalletState.Binding -> BindingPanel()
            is WalletState.Ready -> ReadyPanel(state)
            is WalletState.Failed -> FailedPanel(state.message, onRetry)
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
                text = "Receive address",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.primaryAddress,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = "Anyone with this address can send you XMR. Sprint W2 will " +
                    "bind it to paired peers so you never have to share it manually.",
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
