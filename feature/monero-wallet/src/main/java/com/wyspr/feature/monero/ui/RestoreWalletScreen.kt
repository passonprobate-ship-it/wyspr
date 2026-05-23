package com.wyspr.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyspr.core.ui.components.WysprPanel
import kotlinx.coroutines.launch

/**
 * Restore-from-seed screen. Accepts either:
 *  - 25 Electrum-style words (Monero's default mnemonic format), or
 *  - 64 hex chars (the raw 32-byte spend secret).
 *
 * Both decode to the same 32-byte payload; the layer below
 * ([com.wyspr.feature.monero.MoneroWalletService.restoreFromSeed])
 * doesn't know or care which path the user took.
 *
 * Optional "restore from block height" — most users won't know
 * theirs; default leaves it blank and the engine treats that as
 * genesis. The wallet's first refresh covers the gap; on an old
 * seed that's a long scan.
 *
 * Destructive: triggering the restore wipes the current on-device
 * wallet. The two-step confirm UX (paste → "Restore" button →
 * biometric prompt → actual restore) makes the destructive step
 * intentional.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreWalletScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    biometricPrompt: suspend () -> Boolean,
) {
    val viewModel: RestoreWalletViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var seedInput by remember { mutableStateOf("") }
    var heightInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Restore wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WarningPanel()
            SeedInputPanel(
                seedInput = seedInput,
                onSeedChange = { seedInput = it },
                heightInput = heightInput,
                onHeightChange = {
                    heightInput = it.filter { ch -> ch.isDigit() }
                },
            )
            Button(
                onClick = {
                    scope.launch {
                        val ok = try { biometricPrompt() } catch (_: Throwable) { false }
                        if (ok) {
                            val success = viewModel.restore(
                                seedText = seedInput,
                                restoreHeightText = heightInput,
                            )
                            if (success) onRestored()
                        }
                    }
                },
                enabled = seedInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore")
            }
            val err = state.errorMessage
            if (err != null) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WarningPanel() {
    WysprPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.error,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Restoring replaces your current wallet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Any XMR controlled by the seed currently on this device will become " +
                    "inaccessible from Wyspr unless you've also backed up THAT seed. " +
                    "Only continue if you intend to swap the wallet for the one matching " +
                    "the seed below.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SeedInputPanel(
    seedInput: String,
    onSeedChange: (String) -> Unit,
    heightInput: String,
    onHeightChange: (String) -> Unit,
) {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Seed",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Paste either your 25-word mnemonic or your 64-character spend " +
                    "secret. Either form recovers the same wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = seedInput,
                onValueChange = onSeedChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = { Text("e.g. abbey abbey … (25 words) or 64 hex chars") },
            )
            Text(
                text = "Restore from block height (optional)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "If you know roughly when this wallet first received funds, set the " +
                    "block height to skip earlier chain scan. Leave blank to scan from " +
                    "genesis (slow on old wallets).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = heightInput,
                onValueChange = onHeightChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. 2400000") },
            )
        }
    }
}
