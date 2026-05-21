package com.keystone.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.core.identity.Fingerprint
import com.keystone.core.ui.KeystoneAccent

/**
 * Post-onboarding app home — replaces the wallet's HomeScreen as
 * the landing surface. Six tiles into the working surfaces of the
 * app; nothing about the wallet's balance is implied at this
 * level. The identity strip at the top lets a returning user
 * confirm they're operating from the expected key before they
 * walk into Messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenMessages: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenMyPage: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenFindPeers: () -> Unit,
    onOpenShareApp: () -> Unit,
    onOpenUseMailbox: () -> Unit,
    onOpenBeMailbox: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Keystone", style = MaterialTheme.typography.titleLarge) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IdentityStrip(fingerprint = state.fingerprint)
            HomeTiles(
                unread = state.unread,
                onOpenMessages = onOpenMessages,
                onOpenCommunity = onOpenCommunity,
                onOpenMyPage = onOpenMyPage,
                onOpenWallet = onOpenWallet,
                onOpenFindPeers = onOpenFindPeers,
                onOpenShareApp = onOpenShareApp,
                onOpenUseMailbox = onOpenUseMailbox,
                onOpenBeMailbox = onOpenBeMailbox,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun IdentityStrip(fingerprint: Fingerprint?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "This device",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                fingerprint?.text ?: "—",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun HomeTiles(
    unread: Int,
    onOpenMessages: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenMyPage: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenFindPeers: () -> Unit,
    onOpenShareApp: () -> Unit,
    onOpenUseMailbox: () -> Unit,
    onOpenBeMailbox: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            HomeTile(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Messages",
                subtitle = "1:1 and groups",
                badge = unread.takeIf { it > 0 },
                onClick = onOpenMessages,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.AccountTree,
                title = "My community",
                subtitle = "Trust graph",
                onClick = onOpenCommunity,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.Person,
                title = "My page",
                subtitle = "Onion profile",
                onClick = onOpenMyPage,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.AccountBalanceWallet,
                title = "Wallet",
                subtitle = "Send, receive, audit",
                onClick = onOpenWallet,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.Bluetooth,
                title = "Find peers",
                subtitle = "Add a contact",
                onClick = onOpenFindPeers,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.QrCode2,
                title = "Share app",
                subtitle = "QR over local WiFi",
                onClick = onOpenShareApp,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.MailOutline,
                title = "Use a mailbox",
                subtitle = "Async delivery",
                onClick = onOpenUseMailbox,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.Inbox,
                title = "Be a mailbox",
                subtitle = "Hold for community",
                onClick = onOpenBeMailbox,
            )
        }
        item {
            HomeTile(
                icon = Icons.Filled.Settings,
                title = "Settings",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun HomeTile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                if (badge != null) BadgeBubble(count = badge)
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeBubble(count: Int) {
    val label = if (count > 99) "99+" else count.toString()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(color = KeystoneAccent.Pending, shape = CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
