package com.keystone.feature.onboarding.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.identity.Identity
import com.keystone.core.ui.QrRenderer

@Composable
fun DisplayQrScreen(
    identity: Identity,
    backing: KeystoreManager.Backing,
    qrBase32: String,
    onRefresh: () -> Unit,
    onContinueToWallet: () -> Unit,
    onFindPeers: () -> Unit,
) {
    val qrBitmap = remember(qrBase32) { QrRenderer.render(qrBase32, sizePx = 768) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Your handshake QR",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Hand this device to your peer to scan, or place both devices " +
                "side by side so each can scan the other. Refresh after 5 minutes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Surface(
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Handshake QR",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your fingerprint", style = MaterialTheme.typography.labelLarge)
            Text(
                identity.fingerprint.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(
            "Backing: ${
                when (backing) {
                    KeystoreManager.Backing.STRONGBOX -> "StrongBox (dedicated secure element)"
                    KeystoreManager.Backing.TEE -> "TEE (trusted execution environment)"
                    KeystoreManager.Backing.SOFTWARE_REJECTED -> "Software (REJECTED)"
                }
            }",
            style = MaterialTheme.typography.labelMedium,
        )

        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) { Text("Refresh QR") }

        OutlinedButton(
            onClick = onFindPeers,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Find nearby peers (BLE)") }

        androidx.compose.material3.Button(
            onClick = onContinueToWallet,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue to wallet") }
    }
}
