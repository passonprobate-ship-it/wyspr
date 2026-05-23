package com.keystone.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.AddressValidator
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.atomicUnitsAsXmr
import im.molly.monero.sdk.FeePriority
import kotlinx.coroutines.launch

/**
 * Sweep-all screen. Sends every unlocked enote in the wallet to a
 * single user-supplied address. Used for:
 *  - Retiring the wallet before disposing of the phone (sweep to
 *    your new wallet's address).
 *  - Consolidating dust outputs into one clean spend.
 *
 * Destructive in the "you can't undo a Monero tx" sense — the
 * confirm flow is biometric-gated + has a clearly-worded warning.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SweepWalletScreen(
    onBack: () -> Unit,
    biometricPrompt: suspend () -> Boolean,
) {
    val vm: SweepWalletViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dest by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(FeePriority.Medium) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Sweep wallet") },
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
            WarningPanel(unlocked = state.unlockedAtomic)
            KeystonePanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recipient address",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Paste the Monero address that should receive every coin in " +
                            "this wallet. Double-check — sweep cannot be reversed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val destValid = dest.isNotBlank() && AddressValidator.isValidMonero(dest)
                    OutlinedTextField(
                        value = dest,
                        onValueChange = { dest = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = { Text("4… (Monero address)") },
                        isError = dest.isNotBlank() && !destValid,
                        supportingText = {
                            when {
                                dest.isBlank() -> Unit
                                destValid -> Text(
                                    "✓ Valid Monero address",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                else -> Text(
                                    "Not a recognised Monero address",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                    Text(
                        text = "Fee priority",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FeePriority.entries.forEach { tier ->
                            FilterChip(
                                selected = tier == priority,
                                onClick = { priority = tier },
                                label = { Text(tier.name) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        val ok = try { biometricPrompt() } catch (_: Throwable) { false }
                        if (ok) vm.sweep(dest, priority)
                    }
                },
                enabled = dest.isNotBlank() && AddressValidator.isValidMonero(dest),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sweep entire wallet")
            }
            val result = state.lastResult
            if (result != null) {
                ResultPanel(result)
            }
        }
    }
}

@Composable
private fun WarningPanel(unlocked: Long) {
    KeystonePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.error,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "This empties your wallet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "All ${unlocked.atomicUnitsAsXmr()} XMR currently unlocked in this " +
                    "wallet will be sent to the address below in a single transaction " +
                    "(minus the fee). Locked / pending coins stay behind until they unlock.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ResultPanel(result: MoneroWalletService.SendResult) {
    val (color, text) = when (result) {
        is MoneroWalletService.SendResult.Sent ->
            MaterialTheme.colorScheme.primary to
                "Swept ${result.amountAtomicUnits.atomicUnitsAsXmr()} XMR " +
                "(fee ${result.feeAtomicUnits.atomicUnitsAsXmr()})"
        MoneroWalletService.SendResult.WalletNotReady ->
            MaterialTheme.colorScheme.error to "Wallet not ready — open the Wallet tab first."
        MoneroWalletService.SendResult.NoAddressBound ->
            MaterialTheme.colorScheme.error to "No address — paste a recipient address above."
        MoneroWalletService.SendResult.BroadcastFailed ->
            MaterialTheme.colorScheme.error to "Built locally but daemon refused to broadcast."
        is MoneroWalletService.SendResult.Error ->
            MaterialTheme.colorScheme.error to result.message
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}
