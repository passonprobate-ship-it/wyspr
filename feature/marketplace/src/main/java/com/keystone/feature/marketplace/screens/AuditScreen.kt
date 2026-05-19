package com.keystone.feature.marketplace.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keystone.feature.marketplace.WalletViewModel

@Composable
fun AuditScreen(
    state: WalletViewModel.UiState,
    onRunAudit: ((WalletViewModel.AuditUi) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var result by remember { mutableStateOf<WalletViewModel.AuditUi?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Audit", style = MaterialTheme.typography.titleLarge)
        Text(
            "Recompute the balance for your account from raw envelopes " +
                "and compare with the cached value. Useful after a crash " +
                "or to sanity-check a long history.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            "Cached balance: ${state.balance} Gem",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { onRunAudit { result = it } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Recompute now") }

        result?.let { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (r.ok) "OK — cache matches recompute." else "DRIFT detected",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                    )
                    Text("Cached:     ${r.cachedBalance}", style = MaterialTheme.typography.bodySmall)
                    Text("Recomputed: ${r.recomputedBalance}", style = MaterialTheme.typography.bodySmall)
                    if (!r.ok) {
                        Text("Drift: ${r.drift}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}
