package com.keystone.feature.marketplace.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.keystone.feature.marketplace.WalletViewModel

@Composable
fun SendScreen(
    state: WalletViewModel.UiState,
    sendResult: WalletViewModel.SendUi?,
    onSend: (recipientHex: String, amount: String, memo: String) -> Unit,
    onAcknowledge: () -> Unit,
    onBack: () -> Unit,
) {
    var recipientHex by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Send Gem", style = MaterialTheme.typography.titleLarge)
        Text(
            "Balance: ${state.balance} Gem",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = recipientHex,
            onValueChange = { recipientHex = it },
            label = { Text("Recipient public key (64 hex chars)") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it.filter { c -> c.isDigit() } },
            label = { Text("Amount") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            label = { Text("Memo (optional, hashed only — body sent separately)") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onSend(recipientHex, amount, memo) },
            enabled = recipientHex.isNotBlank() && amount.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign & queue") }

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }

        sendResult?.let { result ->
            val color = when (result) {
                is WalletViewModel.SendUi.Success -> MaterialTheme.colorScheme.primary
                is WalletViewModel.SendUi.Error -> MaterialTheme.colorScheme.error
            }
            val message = when (result) {
                is WalletViewModel.SendUi.Success -> result.message
                is WalletViewModel.SendUi.Error -> result.message
            }
            Text(message, color = color, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAcknowledge) { Text("Dismiss") }
        }
    }
}
