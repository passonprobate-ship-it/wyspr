package com.keystone.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.ui.settings.BiometricSettings

/**
 * Top-level Settings screen reachable from the messaging hub.
 * Deliberately minimal — the wallet feature still has its own
 * advanced settings (community switching, identity reset). This
 * is the "find me the obvious controls" screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    biometricSettings: BiometricSettings,
    onOpenAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    val gate by biometricSettings.gateEnabled.collectAsStateWithLifecycle()
    val bind by biometricSettings.bindToBiometric.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(
                title = "Lock launch with biometric",
                description = "Prompts for fingerprint, face, or device PIN every cold launch. " +
                    "Protects the app surface — your identity key is hardware-bound either way.",
                checked = gate,
                onCheckedChange = biometricSettings::setGateEnabled,
            )
            SettingsCard(
                title = "Bind identity key to biometric",
                description = "When you create a new identity, require biometric/PIN to use it for signing. " +
                    "Cannot be applied to your current identity — only takes effect on the next reset.",
                checked = bind,
                onCheckedChange = biometricSettings::setBindToBiometric,
            )

            // Advanced settings (community switch, identity reset, etc.)
            // still live in the wallet module — link there for users who
            // need them.
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Advanced",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Community switching, identity reset, and wallet preferences live in the Wallet module.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onOpenAdvanced,
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Open advanced settings") }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
