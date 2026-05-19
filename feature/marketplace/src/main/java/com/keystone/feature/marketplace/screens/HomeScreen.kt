package com.keystone.feature.marketplace.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keystone.core.currency.Transfer
import com.keystone.feature.marketplace.WalletViewModel

@Composable
fun HomeScreen(
    state: WalletViewModel.UiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onHistory: () -> Unit,
    onAudit: () -> Unit,
    onSettings: () -> Unit,
    onCommunity: () -> Unit = {},
    onMintDebug: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Gem Wallet", style = MaterialTheme.typography.headlineMedium)

        Card(
            colors = CardDefaults.cardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Balance", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${state.balance} Gem",
                    style = MaterialTheme.typography.displaySmall,
                )
                if (state.slashed) {
                    Text(
                        "ACCOUNT SLASHED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onSend,
                enabled = state.genesisExists && !state.slashed,
                modifier = Modifier.weight(1f),
            ) { Text("Send") }
            OutlinedButton(
                onClick = onReceive,
                modifier = Modifier.weight(1f),
            ) { Text("Receive") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onHistory,
                modifier = Modifier.weight(1f),
            ) { Text("History") }
            OutlinedButton(
                onClick = onAudit,
                modifier = Modifier.weight(1f),
            ) { Text("Audit") }
        }

        OutlinedButton(
            onClick = onCommunity,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("My community") }

        OutlinedButton(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Settings") }

        if (!state.genesisExists && state.loaded) {
            DebugMintCard(onMintDebug = onMintDebug)
        }

        HorizontalDivider()
        Text("Recent", style = MaterialTheme.typography.titleMedium)
        if (state.transfers.isEmpty()) {
            Text(
                "No transfers yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            state.transfers.take(5).forEach { TransferRow(it, mePub = state.meAccount?.bytes) }
        }
    }
}

@Composable
private fun DebugMintCard(onMintDebug: (Long) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "Debug: no genesis in this community yet.",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Mint a starter balance so you can test sends. In a real " +
                    "community, only the founder may issue genesis.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onMintDebug(10_000L) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mint 10,000 Gem to me (debug)") }
        }
    }
}

@Composable
fun TransferRow(transfer: Transfer, mePub: ByteArray?) {
    val isOutbound = mePub != null && transfer.sender.contentEquals(mePub)
    val counterparty = if (isOutbound) transfer.recipient else transfer.sender
    val sign = if (isOutbound) "−" else "+"
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isOutbound) "To" else "From",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "$sign${transfer.amount}",
                style = MaterialTheme.typography.titleMedium,
                color = if (isOutbound) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            shortHex(counterparty),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun shortHex(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "(unknown)"
    val full = bytes.joinToString("") { "%02x".format(it) }
    return full.take(8) + "…" + full.takeLast(4)
}
