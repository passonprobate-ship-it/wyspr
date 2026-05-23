package com.wyspr.feature.marketplace.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyspr.feature.marketplace.WalletViewModel

@Composable
fun HistoryScreen(
    state: WalletViewModel.UiState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("History", style = MaterialTheme.typography.titleLarge)
        Text(
            "${state.transfers.size} transfer(s).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (state.transfers.isEmpty()) {
            Text("Nothing to show yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.transfers) { transfer ->
                    TransferRow(transfer = transfer, mePub = state.meAccount?.bytes)
                    HorizontalDivider()
                }
            }
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}
