package com.keystone.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.atomicUnitsAsXmr
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Transaction detail screen — pushed when the user taps a row in
 * the tx history. Pure read-only view: full hash, block + age,
 * payment destinations (outbound), fee. No edit, no resend.
 *
 * The tx is identified by hash; the screen looks it up in the
 * live wallet state so a tx moving from unconfirmed → confirmed
 * re-renders without navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxDetailScreen(
    txHash: String,
    onBack: () -> Unit,
) {
    val vm: MoneroWalletViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val tx = (state as? MoneroWalletService.WalletState.Ready)?.transactions
        ?.firstOrNull { it.txHash == txHash }
    val tipHeight = (state as? MoneroWalletService.WalletState.Ready)?.transactions
        ?.mapNotNull { it.blockHeight }?.maxOrNull()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Transaction") },
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
            if (tx == null) {
                Text(
                    text = "Transaction not found — it may have been pruned from history.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                HeadlinePanel(tx)
                MetaPanel(tx = tx, tipHeight = tipHeight)
                if (tx.payments.isNotEmpty()) {
                    PaymentsPanel(tx.payments)
                }
                HashPanel(txHash = tx.txHash)
            }
        }
    }
}

@Composable
private fun HeadlinePanel(tx: MoneroWalletService.TxHistoryEntry) {
    val absAmount = kotlin.math.abs(tx.amountAtomicUnits).atomicUnitsAsXmr()
    val sign = if (tx.isInbound) "+" else "−"
    val color = if (tx.isInbound) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.tertiary
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (tx.isInbound) "Received" else "Sent",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$sign $absAmount XMR",
                style = MaterialTheme.typography.headlineMedium,
                color = color,
            )
            if (!tx.isInbound && tx.feeAtomicUnits > 0L) {
                Text(
                    text = "Fee ${tx.feeAtomicUnits.atomicUnitsAsXmr()} XMR",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

@Composable
private fun MetaPanel(tx: MoneroWalletService.TxHistoryEntry, tipHeight: Int?) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyVal(
                "Status",
                if (tx.isUnconfirmed) "Pending (in mempool)" else "Confirmed",
            )
            val height = tx.blockHeight
            if (height != null) {
                KeyVal("Block height", height.toString())
                if (tipHeight != null) {
                    // Confirmations = (tip - txHeight) + 1. We
                    // approximate the tip from the wallet's view of
                    // the chain — equal to the highest block we
                    // know about. Monero needs 10 confirmations
                    // for outbound spendability.
                    val confs = (tipHeight - height + 1).coerceAtLeast(0)
                    KeyVal("Confirmations", confs.toString())
                }
            }
            val ts = tx.blockTimestamp
            if (ts != null) {
                KeyVal("Confirmed at", TS_FORMAT.format(ts))
            }
            KeyVal("Atomic units", "${tx.amountAtomicUnits} pXMR")
        }
    }
}

@Composable
private fun PaymentsPanel(payments: List<MoneroWalletService.PaymentDest>) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Sent to",
                style = MaterialTheme.typography.titleMedium,
            )
            payments.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${p.amountAtomicUnits.atomicUnitsAsXmr()} XMR",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = p.address,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HashPanel(txHash: String) {
    val clipboard = LocalClipboardManager.current
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Transaction hash",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = txHash,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(txHash)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy hash")
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString("https://xmrchain.net/tx/$txHash"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy explorer URL")
            }
            Text(
                text = "Open the copied URL in Tor Browser to look up the transaction " +
                    "without revealing your IP to xmrchain.net.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyVal(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
