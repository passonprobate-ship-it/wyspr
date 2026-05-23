package com.keystone.feature.monero.ui

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
import com.keystone.core.ui.QrRenderer
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.monero.MoneroWalletService

/**
 * Receive-XMR screen. Renders the wallet's primary address as a
 * QR code so out-of-band sharing (screen-to-screen scan, paper
 * print, in-person) doesn't require typing the address. The text
 * form is shown below for fallback / copy.
 *
 * Address shown is the primary (account 0, sub 0) — not a peer-
 * scoped subaddress. For paired peers the auto-exchange path
 * already gives them their own subaddress; this screen is for
 * anyone-can-pay-you scenarios (e.g. shared with a non-Keystone
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
                QrPanel(address = address)
                AddressPanel(
                    address = address,
                    onCopy = { clipboard.setText(AnnotatedString(address)) },
                )
            }
        }
    }
}

@Composable
private fun QrPanel(address: String) {
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
        // Compute a sensible size based on the screen — 280dp is
        // big enough to scan at arm's length but leaves room for
        // the address below. The Bitmap is re-rendered only when
        // the address or size changes.
        val density = LocalDensity.current
        val sizePx = with(density) { 280.dp.roundToPx() }
        val bitmap = remember(address, sizePx) {
            QrRenderer.render(address, sizePx)
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
                contentDescription = "Receive address QR",
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
    }
}

@Composable
private fun AddressPanel(address: String, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    KeystonePanel(modifier = Modifier.fillMaxWidth()) {
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
                    onCopy()
                    copied = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (copied) "Copied" else "Copy address")
            }
            Text(
                text = "Anyone with this address can send you XMR. For paired peers, " +
                    "Keystone auto-exchanges a unique subaddress per relationship — this " +
                    "primary address is for everyone else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
