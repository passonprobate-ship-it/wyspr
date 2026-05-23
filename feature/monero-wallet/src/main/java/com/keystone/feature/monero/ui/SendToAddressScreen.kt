package com.keystone.feature.monero.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.AddressValidator
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.atomicUnitsAsXmr
import im.molly.monero.sdk.FeePriority
import kotlinx.coroutines.launch

/**
 * Send-to-address screen. Used to pay any XMR address that isn't
 * a paired-peer auto-binding — exchange withdrawal targets,
 * merchants, etc.
 *
 * Three input affordances:
 *   - Paste an address directly
 *   - Pick from the saved address book (sorted most-recently-used)
 *   - Optionally save a fresh address with a label so it shows
 *     up in the book next time
 *
 * Biometric prompt fires before the wallet builds the tx —
 * spending real money is never a single-tap action.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendToAddressScreen(
    onBack: () -> Unit,
    biometricPrompt: suspend () -> Boolean,
) {
    val vm: SendToAddressViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var address by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var saveLabel by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(FeePriority.Medium) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Send to address") },
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
            if (state.book.isNotEmpty()) {
                AddressBookPanel(
                    rows = state.book,
                    onPick = { entry ->
                        address = entry.address
                        saveLabel = entry.label
                    },
                    onDelete = { vm.deleteFromBook(it.label) },
                )
            }
            ComposePanel(
                address = address,
                onAddressChange = { address = it.trim() },
                amountStr = amountStr,
                onAmountChange = { amountStr = it.filter { c -> c.isDigit() || c == '.' } },
                saveLabel = saveLabel,
                onSaveLabelChange = { saveLabel = it },
                priority = priority,
                onPriorityChange = { priority = it },
                feeEstimate = state.feeEstimateAtomic,
            )
            val atomic = amountStr.toBigDecimalOrNull()
                ?.multiply(java.math.BigDecimal(1_000_000_000_000L))
                ?.toLong() ?: 0L
            val addressValid = address.isNotBlank() && AddressValidator.isValidMonero(address)
            Button(
                onClick = {
                    scope.launch {
                        val ok = try { biometricPrompt() } catch (_: Throwable) { false }
                        if (ok) {
                            vm.send(
                                address = address,
                                amountAtomicUnits = atomic,
                                feePriority = priority,
                                saveLabel = saveLabel.takeIf { it.isNotBlank() },
                            )
                        }
                    }
                },
                enabled = addressValid && atomic > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send")
            }
            val result = state.lastResult
            if (result != null) {
                ResultLine(result)
            }
        }
    }
}

@Composable
private fun AddressBookPanel(
    rows: List<com.keystone.core.database.entities.AddressBookEntity>,
    onPick: (com.keystone.core.database.entities.AddressBookEntity) -> Unit,
    onDelete: (com.keystone.core.database.entities.AddressBookEntity) -> Unit,
) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Saved addresses",
                style = MaterialTheme.typography.titleMedium,
            )
            rows.forEachIndexed { idx, entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry) },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = entry.label, style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { onDelete(entry) }) { Text("Remove") }
                    }
                    Text(
                        text = entry.address,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (idx < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposePanel(
    address: String,
    onAddressChange: (String) -> Unit,
    amountStr: String,
    onAmountChange: (String) -> Unit,
    saveLabel: String,
    onSaveLabelChange: (String) -> Unit,
    priority: FeePriority,
    onPriorityChange: (FeePriority) -> Unit,
    feeEstimate: Map<FeePriority, Long>,
) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Recipient",
                style = MaterialTheme.typography.titleMedium,
            )
            val validity = remember(address) {
                when {
                    address.isBlank() -> AddressValidity.EMPTY
                    AddressValidator.isValidMonero(address) -> AddressValidity.VALID
                    else -> AddressValidity.INVALID
                }
            }
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text("4… (Monero address)") },
                isError = validity == AddressValidity.INVALID,
                supportingText = {
                    when (validity) {
                        AddressValidity.EMPTY -> Unit
                        AddressValidity.VALID -> Text(
                            "✓ Valid Monero address",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AddressValidity.INVALID -> Text(
                            "Not a recognised Monero address",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
            OutlinedTextField(
                value = saveLabel,
                onValueChange = onSaveLabelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Optional label (saves to address book)") },
            )
            OutlinedTextField(
                value = amountStr,
                onValueChange = onAmountChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("0.00") },
                suffix = { Text("XMR") },
            )
            Text(
                text = "Fee priority",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FeePriority.entries.forEach { tier ->
                    val est = feeEstimate[tier]
                    FilterChip(
                        selected = tier == priority,
                        onClick = { onPriorityChange(tier) },
                        label = {
                            Text(
                                if (est != null) {
                                    "${tier.name} · ~${est.atomicUnitsAsXmr()} XMR"
                                } else {
                                    tier.name
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

private enum class AddressValidity { EMPTY, VALID, INVALID }

@Composable
private fun ResultLine(result: MoneroWalletService.SendResult) {
    val (color, text) = when (result) {
        is MoneroWalletService.SendResult.Sent ->
            MaterialTheme.colorScheme.primary to
                "Sent ${result.amountAtomicUnits.atomicUnitsAsXmr()} XMR " +
                "(fee ${result.feeAtomicUnits.atomicUnitsAsXmr()})"
        MoneroWalletService.SendResult.WalletNotReady ->
            MaterialTheme.colorScheme.error to "Wallet not ready — open the Wallet tab first."
        MoneroWalletService.SendResult.NoAddressBound ->
            MaterialTheme.colorScheme.error to "No address — paste one above."
        MoneroWalletService.SendResult.BroadcastFailed ->
            MaterialTheme.colorScheme.error to "Built locally but daemon refused to broadcast."
        is MoneroWalletService.SendResult.Error ->
            MaterialTheme.colorScheme.error to result.message
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}
