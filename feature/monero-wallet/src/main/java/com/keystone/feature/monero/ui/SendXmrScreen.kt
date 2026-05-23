package com.keystone.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.keystone.core.identity.PublicKey
import kotlinx.coroutines.launch
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.atomicUnitsAsXmr

/**
 * Sprint W2 send-XMR screen, reached from the conversation peer-
 * detail sheet via NavHost. Owns:
 *
 *  - If the peer has a bound Monero address (from
 *    `PeerPaymentAddressDao`), show "Send N XMR to [name]" with
 *    amount entry + confirm.
 *  - If no address is bound, show a paste-the-peer's-address field
 *    so the user can supply it once. Future polish: auto-exchange
 *    via the messaging sync round so manual paste isn't needed.
 *  - "My XMR address" tile with copy-to-clipboard so the user can
 *    share it back to the peer out-of-band.
 *
 * No biometric gate yet (Sprint W3 polish) — Send tap is a single
 * confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendXmrScreen(
    peer: PublicKey,
    displayName: String?,
    onBack: () -> Unit,
    /**
     * Sprint W3: spending real money requires biometric confirm.
     * Wired by the NavHost — same prompt the Marketplace + other
     * sensitive flows already use. Returns true iff the user
     * authenticated; false (or thrown exception) means abort send.
     */
    biometricPrompt: suspend () -> Boolean = { true },
    viewModel: SendXmrViewModel = hiltViewModel(),
) {
    LaunchedEffect(peer.bytes.contentHashCode()) { viewModel.bind(peer) }
    val state by viewModel.state.collectAsState()
    // Prefer the contact-table lookup over whatever the navigator
    // happened to pass in — the VM is the source of truth for the
    // peer's display name.
    val resolvedName = state.displayName ?: displayName
    val peerLabel = resolvedName?.takeIf { it.isNotBlank() }
        ?: peer.fingerprint.toString().take(20)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Send XMR to $peerLabel") },
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
            MyAddressPanel(state.myAddress)
            val bound = state.peerBoundAddress
            if (bound != null) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                BoundPanel(
                    bound = bound,
                    onSend = { atomic ->
                        scope.launch {
                            // Biometric confirm BEFORE building the
                            // tx — abort cleanly if the user backs
                            // out or fails auth. Spending real money
                            // is never a single-tap action.
                            val ok = try { biometricPrompt() } catch (_: Throwable) { false }
                            if (ok) viewModel.send(atomic)
                        }
                    },
                    lastResult = state.lastSendResult,
                )
            } else {
                UnboundPanel(onBind = viewModel::bindPastedAddress)
            }
        }
    }
}

@Composable
private fun MyAddressPanel(myAddress: String?) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Your receive address",
                style = MaterialTheme.typography.titleMedium,
            )
            if (myAddress == null) {
                Text(
                    text = "Wallet still loading — open the Wallet tab once to bootstrap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = myAddress,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    text = "Share this with your peer so they can send XMR back to you. " +
                        "Sprint W3 will auto-exchange these over the messaging channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BoundPanel(
    bound: String,
    onSend: (Long) -> Unit,
    lastResult: MoneroWalletService.SendResult?,
) {
    var amountStr by remember { mutableStateOf("") }
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Sending to",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = bound,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = amountStr,
                onValueChange = { new -> amountStr = new.filter { it.isDigit() || it == '.' } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("0.00") },
                suffix = { Text("XMR") },
            )
            val atomic = amountStr.toBigDecimalOrNull()
                ?.multiply(java.math.BigDecimal(1_000_000_000_000L))
                ?.toLong() ?: 0L
            Button(
                onClick = { onSend(atomic) },
                enabled = atomic > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send")
            }
            if (lastResult != null) {
                ResultLine(lastResult)
            }
        }
    }
}

@Composable
private fun UnboundPanel(onBind: (String) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "No address bound for this peer yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Ask the peer to share their Monero address, then paste it below. " +
                    "Future versions will exchange addresses automatically over the messaging " +
                    "channel — for now it's a one-time paste.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4,
                placeholder = { Text("4… (Monero address)") },
            )
            OutlinedButton(
                onClick = { onBind(pasted) },
                enabled = pasted.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Bind this address")
            }
        }
    }
}

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
            MaterialTheme.colorScheme.error to "No address bound. Paste one and try again."
        MoneroWalletService.SendResult.BroadcastFailed ->
            MaterialTheme.colorScheme.error to "Built locally but daemon refused to broadcast."
        is MoneroWalletService.SendResult.Error ->
            MaterialTheme.colorScheme.error to result.message
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}
