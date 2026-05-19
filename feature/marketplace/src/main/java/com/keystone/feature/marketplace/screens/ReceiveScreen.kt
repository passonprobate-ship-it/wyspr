package com.keystone.feature.marketplace.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keystone.feature.marketplace.WalletViewModel

@Composable
fun ReceiveScreen(
    state: WalletViewModel.UiState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Receive Gem", style = MaterialTheme.typography.titleLarge)
        Text(
            "Share your fingerprint with the sender. They paste your " +
                "public key into their Send screen. There is no separate " +
                "receive QR — your identity is the address.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Fingerprint", style = MaterialTheme.typography.labelLarge)
                Text(state.meFingerprint, style = MaterialTheme.typography.titleMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Public key (hex)", style = MaterialTheme.typography.labelLarge)
                Text(
                    state.meAccount?.let { it.bytes.joinToString("") { b -> "%02x".format(b) } }
                        ?: "(not loaded)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}
