package com.keystone.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.keystone.app.profile.PeerUpdatesBanner
import com.keystone.core.identity.GroupId
import com.keystone.core.identity.PublicKey
import com.keystone.core.ui.settings.BiometricSettings
import com.keystone.feature.marketplace.screens.CommunityScreen
import com.keystone.feature.messaging.screens.ConversationListScreen

/**
 * Top-level UI shell with bottom navigation. Replaces the prior tile-
 * grid Home — three persistent tabs (Chats, Community, Settings) match
 * the WhatsApp/Signal mental model: one thumb tap to switch between
 * the surfaces a user actually touches every session. Wallet,
 * Mailbox, My Page, Find Peers, etc., now live behind Settings rather
 * than as separate tiles.
 *
 * `initialChatPeerHex` is forwarded to the Messaging tab so a
 * notification deep-link still opens straight into the right thread.
 */
@Composable
fun MainShell(
    biometricSettings: BiometricSettings,
    biometricPrompt: suspend () -> Boolean,
    onOpenMailbox: () -> Unit,
    onOpenMyPage: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenFindPeers: () -> Unit,
    onOpenShareApp: () -> Unit,
    onOpenCommunityGraph: () -> Unit,
    /** Open a 1:1 thread as a full-screen route (covers the bottom nav). */
    onOpenThread: (PublicKey) -> Unit,
    /** Open a group thread as a full-screen route. */
    onOpenGroup: (GroupId) -> Unit,
    /** Open the create-group screen as a full-screen route. */
    onCreateGroup: () -> Unit,
    onIdentityReset: () -> Unit,
) {
    // rememberSaveable so the tab persists across process death.
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Chats -> ConversationListScreen(
                    onOpenThread = onOpenThread,
                    onOpenGroup = onOpenGroup,
                    onCreateGroup = onCreateGroup,
                    // Bottom-nav tab is its own root; no Back button here.
                    onBack = {},
                    onOpenSettings = { tab = Tab.Settings },
                    onOpenFindPeers = onOpenFindPeers,
                    bannerSlot = { PeerUpdatesBanner(onPairPeer = onOpenFindPeers) },
                )
                Tab.Community -> CommunityScreen(
                    onBack = { /* root tab */ },
                    onOpenGraph = onOpenCommunityGraph,
                )
                Tab.Settings -> AppSettingsScreen(
                    biometricSettings = biometricSettings,
                    onOpenMailbox = onOpenMailbox,
                    onOpenMyPage = onOpenMyPage,
                    onOpenWallet = onOpenWallet,
                    onOpenFindPeers = onOpenFindPeers,
                    onOpenShareApp = onOpenShareApp,
                    onIdentityReset = onIdentityReset,
                    biometricPrompt = biometricPrompt,
                    onBack = { /* root tab */ },
                )
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.AutoMirrored.Filled.Chat),
    Community("Community", Icons.Filled.AccountTree),
    Settings("Settings", Icons.Filled.Settings),
}
