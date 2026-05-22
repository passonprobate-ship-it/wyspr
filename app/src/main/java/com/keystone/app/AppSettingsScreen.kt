package com.keystone.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.ui.settings.BiometricSettings
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Settings — flat list of every controllable surface. Replaces the
 * previous tile-grid Home as the catchall for everything that isn't a
 * primary tab. Sections are visually separated by dividers; rows are
 * single-line, tappable, and lead to dedicated screens for anything
 * that has more than one knob.
 *
 * No more dead-end "Advanced" link: identity reset and any wallet-side
 * preferences are surfaced inline rather than pushed behind a second
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    biometricSettings: BiometricSettings,
    onOpenMailbox: () -> Unit,
    onOpenMyPage: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenFindPeers: () -> Unit,
    onOpenShareApp: () -> Unit,
    onIdentityReset: () -> Unit,
    biometricPrompt: suspend () -> Boolean,
    onBack: () -> Unit,
) {
    val gate by biometricSettings.gateEnabled.collectAsStateWithLifecycle()
    val bind by biometricSettings.bindToBiometric.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Identity")
            LinkRow(
                icon = Icons.Filled.Person,
                title = "My page",
                subtitle = "Edit the profile served at your .onion",
                onClick = onOpenMyPage,
            )
            HorizontalDivider()
            ToggleRow(
                title = "Lock launch with biometric",
                subtitle = "Prompt every cold launch.",
                checked = gate,
                onCheckedChange = biometricSettings::setGateEnabled,
            )
            HorizontalDivider()
            ToggleRow(
                title = "Bind identity key to biometric",
                subtitle = "Requires biometric to sign. Takes effect on next identity reset.",
                checked = bind,
                onCheckedChange = biometricSettings::setBindToBiometric,
            )

            SectionHeader("Network")
            LinkRow(
                icon = Icons.Filled.Mail,
                title = "Mailbox",
                subtitle = "Async delivery — use a friend's or host one yourself",
                onClick = onOpenMailbox,
            )
            HorizontalDivider()
            LinkRow(
                icon = Icons.Filled.Bluetooth,
                title = "Pair a new peer",
                subtitle = "Scan QR codes in person to start a trusted chat",
                onClick = onOpenFindPeers,
            )

            SectionHeader("Wallet")
            LinkRow(
                icon = Icons.Filled.AccountBalanceWallet,
                title = "Open wallet",
                subtitle = "Send, receive, audit. Community switching lives here.",
                onClick = onOpenWallet,
            )

            SectionHeader("Distribute")
            LinkRow(
                icon = Icons.Filled.QrCode2,
                title = "Share Keystone",
                subtitle = "QR over local WiFi for nearby installs",
                onClick = onOpenShareApp,
            )

            SectionHeader("Danger zone")
            LinkRow(
                icon = Icons.Filled.Refresh,
                title = "Reset identity",
                subtitle = "Wipes every key + every paired peer. Cannot be undone.",
                onClick = {
                    scope.launch {
                        if (biometricPrompt()) onIdentityReset()
                    }
                },
            )

            Spacer(modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
