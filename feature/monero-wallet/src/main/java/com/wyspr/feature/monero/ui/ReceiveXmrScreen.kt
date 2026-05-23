package com.wyspr.feature.monero.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wyspr.core.ui.QrRenderer
import com.wyspr.core.ui.components.WysprPanel
import com.wyspr.feature.monero.MoneroWalletService

/**
 * Receive-XMR screen. Renders the wallet's primary address as a
 * QR code so out-of-band sharing (screen-to-screen scan, paper
 * print, in-person) doesn't require typing the address. The text
 * form is shown below for fallback / copy.
 *
 * Address shown is the primary (account 0, sub 0) — not a peer-
 * scoped subaddress. For paired peers the auto-exchange path
 * already gives them their own subaddress; this screen is for
 * anyone-can-pay-you scenarios (e.g. shared with a non-Wyspr
 * sender, printed on a poster, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveXmrScreen(onBack: () -> Unit) {
    val vm: MoneroWalletViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val address = (state as? MoneroWalletService.WalletState.Ready)?.primaryAddress
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Receive XMR") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (address == null) {
                Text(
                    text = "Wallet still loading — try again once the balance appears.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                var amountStr by remember { mutableStateOf("") }
                var labelStr by remember { mutableStateOf("") }
                val payload = remember(address, amountStr, labelStr) {
                    buildMoneroUri(address, amountStr, labelStr)
                }
                QrPanel(payload = payload)
                InvoicePanel(
                    amount = amountStr,
                    onAmountChange = { v -> amountStr = v.filter { it.isDigit() || it == '.' } },
                    label = labelStr,
                    onLabelChange = { labelStr = it },
                )
                AddressPanel(
                    address = address,
                    payload = payload,
                    onCopyAddress = { clipboard.setText(AnnotatedString(address)) },
                    onCopyUri = { clipboard.setText(AnnotatedString(payload)) },
                )
            }
        }
    }
}

@Composable
private fun QrPanel(payload: String) {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        // Compute a sensible size based on the screen — 280dp is
        // big enough to scan at arm's length but leaves room for
        // the address below. The Bitmap is re-rendered only when
        // the payload or size changes (so toggling amount or label
        // mints a fresh QR).
        val density = LocalDensity.current
        val sizePx = with(density) { 280.dp.roundToPx() }
        val bitmap = remember(payload, sizePx) {
            QrRenderer.render(payload, sizePx)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Receive QR",
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
    }
}

@Composable
private fun InvoicePanel(
    amount: String,
    onAmountChange: (String) -> Unit,
    label: String,
    onLabelChange: (String) -> Unit,
) {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Request a specific amount (optional)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Filling these in encodes a Monero URI in the QR above — the " +
                    "sender's wallet pre-fills the amount and description on scan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("0.00") },
                suffix = { Text("XMR") },
            )
            OutlinedTextField(
                value = label,
                onValueChange = onLabelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Description (e.g. \"Coffee\")") },
            )
        }
    }
}

@Composable
private fun AddressPanel(
    address: String,
    payload: String,
    onCopyAddress: () -> Unit,
    onCopyUri: () -> Unit,
) {
    var copiedAddress by remember { mutableStateOf(false) }
    var copiedUri by remember { mutableStateOf(false) }
    LaunchedEffect(copiedAddress) {
        if (copiedAddress) {
            kotlinx.coroutines.delay(1200)
            copiedAddress = false
        }
    }
    LaunchedEffect(copiedUri) {
        if (copiedUri) {
            kotlinx.coroutines.delay(1200)
            copiedUri = false
        }
    }
    val hasInvoice = payload != address
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Your address",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedButton(
                onClick = {
                    onCopyAddress()
                    copiedAddress = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (copiedAddress) "Copied" else "Copy address")
            }
            if (hasInvoice) {
                OutlinedButton(
                    onClick = {
                        onCopyUri()
                        copiedUri = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (copiedUri) "Copied" else "Copy invoice URI")
                }
            }
            Text(
                text = "Anyone with this address can send you XMR. For paired peers, " +
                    "Wyspr auto-exchanges a unique subaddress per relationship — this " +
                    "primary address is for everyone else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Build a Monero URI per the BIP21-style scheme:
 *
 *   `monero:<address>[?tx_amount=N][&tx_description=L]`
 *
 * When [amount] is blank and [label] is blank, returns the bare
 * address — wallets scanning this still parse it, but there's no
 * invoice. When either field is set, parameters are URL-encoded
 * and joined with `&`.
 */
private fun buildMoneroUri(address: String, amount: String, label: String): String {
    val params = mutableListOf<String>()
    val amt = amount.trim()
    if (amt.isNotEmpty() && amt.toDoubleOrNull() != null && amt != "." && amt != "0") {
        params += "tx_amount=$amt"
    }
    val lbl = label.trim()
    if (lbl.isNotEmpty()) {
        params += "tx_description=" + java.net.URLEncoder.encode(lbl, "UTF-8")
    }
    return if (params.isEmpty()) address
    else "monero:$address?" + params.joinToString("&")
}
