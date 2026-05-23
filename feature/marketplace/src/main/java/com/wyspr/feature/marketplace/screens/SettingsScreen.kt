package com.wyspr.feature.marketplace.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Stateless settings screen — caller wires the StateFlow + setter.
 * Lives here so the wallet nav graph can reach it; future Settings
 * groups (notifications, debug toggles) attach to this same screen.
 */
@Composable
fun SettingsScreen(
    biometricGateEnabled: Boolean,
    onBiometricGateChange: (Boolean) -> Unit,
    bindIdentityRequested: Boolean,
    onBindIdentityRequestedChange: (Boolean) -> Unit,
    identityIsBound: Boolean,
    activeCommunityHex: String,
    onSwitchCommunity: (String) -> Unit,
    switchCommunityFeedback: String?,
    onResetIdentity: () -> Unit,
    onBack: () -> Unit,
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    var pasteHex by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lock launch with biometric", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Prompts for fingerprint, face, or device PIN every cold launch. " +
                                "App-level gate only.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = biometricGateEnabled,
                        onCheckedChange = onBiometricGateChange,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Bind identity key to biometric",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (identityIsBound) {
                                "Your current identity IS bound: signing requires biometric " +
                                    "or device-credential auth. The wrapping key is unlocked " +
                                    "for 5 minutes after each prompt."
                            } else {
                                "Your current identity is NOT bound. Hardware key still " +
                                    "protects it from extraction, but no biometric is required " +
                                    "to use it. Toggle ON to bind the NEXT identity you create " +
                                    "(this setting cannot be applied to the existing one)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = bindIdentityRequested,
                        onCheckedChange = onBindIdentityRequestedChange,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Active community", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Your wallet, your discovery, and your sync sessions " +
                        "all use this community ID. v0 puts each device in " +
                        "its own community at onboarding — to put two " +
                        "devices on the same community for testing, paste " +
                        "the other device's ID below.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    activeCommunityHex,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = pasteHex,
                    onValueChange = { pasteHex = it },
                    label = { Text("Switch to community (64 hex chars)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onSwitchCommunity(pasteHex)
                        pasteHex = ""
                    },
                    enabled = pasteHex.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Switch community") }
                switchCommunityFeedback?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Danger zone",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Reset identity wipes your hardware key, your seed, your " +
                        "ledger, and every envelope this device has stored. " +
                        "There is no recovery. Use this to test the bound-key " +
                        "flow, or if you want to start over fresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(
                    onClick = { showResetConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset identity") }
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset identity?") },
            text = {
                Text(
                    "This permanently deletes your hardware key, seed, " +
                        "wallet, and ledger. The app will return to the " +
                        "onboarding screen. There is no recovery."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        onResetIdentity()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
