package com.keystone.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keystone.app.biometric.BiometricUnlocker
import com.keystone.app.permissions.rememberBlePermissionGate
import com.keystone.app.profile.EditProfileScreen
import com.keystone.app.profile.ViewProfileScreen
import com.keystone.core.identity.GroupId
import com.keystone.core.identity.PublicKey
import com.keystone.core.ui.settings.BiometricSettings
import com.keystone.feature.marketplace.MarketplaceRoot
import com.keystone.feature.monero.MoneroWalletRoot
import com.keystone.feature.monero.ui.SendXmrScreen
import com.keystone.feature.marketplace.screens.CommunityGraphScreen
import com.keystone.feature.messaging.mailbox.screens.MailboxScreen
import com.keystone.feature.messaging.mailbox.screens.ScanMailboxQrScreen
import com.keystone.feature.messaging.screens.ConversationScreen
import com.keystone.feature.messaging.screens.CreateGroupScreen
import com.keystone.feature.messaging.screens.GroupConversationScreen
import com.keystone.feature.onboarding.OnboardingRoot
import com.keystone.feature.onboarding.screens.DiscoveryScreen
import com.keystone.feature.onboarding.screens.ShareApkScreen
import com.keystone.feature.onboarding.screens.UpdateFromPeerScreen

/**
 * Top-level navigation graph. Post-onboarding lands on [MainShell] —
 * a three-tab bottom-nav (Chats / Community / Settings). Every other
 * surface (Wallet, Mailbox, My Page, Find Peers, Share App) is a
 * full-screen pushed on top of the shell rather than a peer tab.
 *
 * The graph deliberately routes through `onboarding` first and refuses
 * to expose surfaces until a trust edge exists. There is no "skip
 * onboarding" path.
 */
@Composable
fun KeystoneNavHost(
    unlocker: BiometricUnlocker,
    biometricSettings: BiometricSettings,
    pendingDeepLink: MainActivity.DeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val activity = LocalContext.current as? FragmentActivity

    // Honour deep-links posted by the messaging notifier. The pending
    // value re-fires whenever MainActivity emits — both at cold-start
    // (the launching intent) and onNewIntent. After navigating we tell
    // the activity the link was consumed so a rotation doesn't
    // re-trigger.
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink is MainActivity.DeepLink.OpenChat) {
            // Deep-links open the conversation directly as a full-screen
            // route (covers the bottom nav), matching WhatsApp-style
            // notification-tap navigation. Back lands on the Chats tab.
            navController.navigate("${Routes.Conversation}/${pendingDeepLink.peerHex}") {
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }
    val biometricPrompt: suspend () -> Boolean = remember(activity, unlocker) {
        {
            if (activity == null) true
            else unlocker.authenticate(
                activity = activity,
                title = "Unlock Keystone identity",
                subtitle = "Authenticate to use your hardware-protected key.",
            )
        }
    }

    NavHost(navController = navController, startDestination = Routes.Onboarding) {
        composable(Routes.Onboarding) {
            val blePermissionGate = rememberBlePermissionGate()
            OnboardingRoot(
                // onContinueToWallet kept its old hook name; we now land
                // on the bottom-nav shell which is the front door for
                // every working surface.
                onContinueToWallet = {
                    navController.navigate(Routes.Main) {
                        popUpTo(Routes.Onboarding) { inclusive = false }
                    }
                },
                onFindPeers = { navController.navigate(Routes.Discovery) },
                biometricPrompt = biometricPrompt,
                blePermissionGate = blePermissionGate,
            )
        }
        composable(Routes.Main) {
            MainShell(
                biometricSettings = biometricSettings,
                biometricPrompt = biometricPrompt,
                onOpenMailbox = { navController.navigate(Routes.Mailbox) },
                onOpenMyPage = { navController.navigate(Routes.MyPage) },
                onOpenWallet = { navController.navigate(Routes.Monero) },
                onOpenFindPeers = { navController.navigate(Routes.PairPeer) },
                onOpenShareApp = { navController.navigate(Routes.ShareApp) },
                onOpenCommunityGraph = { navController.navigate(Routes.MyCommunityGraph) },
                onOpenThread = { peer ->
                    navController.navigate("${Routes.Conversation}/${peer.bytes.toHex()}")
                },
                onOpenGroup = { groupId ->
                    navController.navigate("${Routes.Group}/${groupId.bytes.toHex()}")
                },
                onCreateGroup = { navController.navigate(Routes.CreateGroup) },
                onIdentityReset = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "${Routes.Conversation}/{peerHex}",
            arguments = listOf(navArgument("peerHex") { type = NavType.StringType }),
        ) { entry ->
            val hex = entry.arguments?.getString("peerHex")
            val bytes = hex?.hexToBytesOrNull()
            if (bytes == null || bytes.size != 32) {
                navController.popBackStack()
                return@composable
            }
            ConversationScreen(
                peer = PublicKey(bytes),
                onBack = { navController.popBackStack() },
                onViewPeerPage = { navController.navigate("${Routes.PeerPage}/${hex}") },
                onSendXmr = { navController.navigate("${Routes.SendXmr}/${hex}") },
            )
        }
        composable(
            route = "${Routes.Group}/{groupIdHex}",
            arguments = listOf(navArgument("groupIdHex") { type = NavType.StringType }),
        ) { entry ->
            val hex = entry.arguments?.getString("groupIdHex")
            val bytes = hex?.hexToBytesOrNull()
            if (bytes == null) {
                navController.popBackStack()
                return@composable
            }
            GroupConversationScreen(
                groupId = GroupId(bytes),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CreateGroup) {
            CreateGroupScreen(
                onBack = { navController.popBackStack() },
                onCreated = { groupId ->
                    navController.navigate("${Routes.Group}/${groupId.bytes.toHex()}") {
                        // Don't keep create-group on the back stack.
                        popUpTo(Routes.Main) { inclusive = false }
                    }
                },
            )
        }
        composable(Routes.Mailbox) {
            MailboxScreen(
                onBack = { navController.popBackStack() },
                onScan = { navController.navigate(Routes.MailboxScan) },
            )
        }
        composable(Routes.MailboxScan) {
            ScanMailboxQrScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ShareApp) {
            ShareApkScreen(
                onDone = { navController.popBackStack() },
                onUpdateFromPeer = { navController.navigate(Routes.UpdateFromPeer) },
            )
        }
        composable(Routes.UpdateFromPeer) {
            UpdateFromPeerScreen(onDone = { navController.popBackStack() })
        }
        composable(Routes.MyCommunityGraph) {
            CommunityGraphScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Discovery) {
            DiscoveryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PairPeer) {
            // Re-entrant QR handshake flow for adding a new peer
            // after onboarding. Mounts OnboardingRoot with startFresh
            // so AlreadyOnboarded routes back into RolePicker instead
            // of bouncing to Main. The whole RolePicker → Pair →
            // Compare → Run → Result path is reused unchanged.
            val blePermissionGate = rememberBlePermissionGate()
            OnboardingRoot(
                onContinueToWallet = { navController.popBackStack() },
                onFindPeers = { /* not used from this entry */ },
                biometricPrompt = biometricPrompt,
                blePermissionGate = blePermissionGate,
                startFresh = true,
            )
        }
        composable(Routes.Monero) {
            MoneroWalletRoot()
        }
        composable(
            route = "${Routes.SendXmr}/{peerHex}",
            arguments = listOf(navArgument("peerHex") { type = NavType.StringType }),
        ) { entry ->
            val hex = entry.arguments?.getString("peerHex")
            val bytes = hex?.hexToBytesOrNull()
            if (bytes == null || bytes.size != 32) {
                navController.popBackStack()
                return@composable
            }
            SendXmrScreen(
                peer = PublicKey(bytes),
                // Display name is best-effort — the SendXmrScreen
                // falls back to the fingerprint when null. We could
                // look it up via ContactDao but for v1 keeping the
                // signature simple wins.
                displayName = null,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Marketplace) {
            MarketplaceRoot(
                biometricPrompt = biometricPrompt,
                onIdentityReset = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onOpenMessaging = {
                    // Wallet → "open messaging" pops back to the shell.
                    navController.popBackStack(Routes.Main, inclusive = false)
                },
            )
        }
        composable(
            route = "${Routes.PeerPage}/{peerHex}",
            arguments = listOf(navArgument("peerHex") { type = NavType.StringType }),
        ) { entry ->
            val hex = entry.arguments?.getString("peerHex")
            val bytes = hex?.hexToBytesOrNull()
            if (bytes == null || bytes.size != 32) {
                navController.popBackStack()
                return@composable
            }
            ViewProfileScreen(peer = PublicKey(bytes), onBack = { navController.popBackStack() })
        }
        composable(Routes.MyPage) {
            EditProfileScreen(onBack = { navController.popBackStack() })
        }
    }
}

object Routes {
    const val Onboarding = "onboarding"
    /** Post-onboarding root — bottom-nav shell. */
    const val Main = "main"
    const val Discovery = "discovery"
    /** Re-entrant QR-handshake flow for adding a new peer after onboarding. */
    const val PairPeer = "pair_peer"
    const val Marketplace = "marketplace"
    const val MyCommunityGraph = "my_community_graph"
    const val MyPage = "my_page"
    const val PeerPage = "peer_page"
    const val ShareApp = "share_app"
    const val UpdateFromPeer = "update_from_peer"
    const val Mailbox = "mailbox"
    const val MailboxScan = "mailbox_scan"
    /** Sprint W1: mollyim-backed Monero wallet. */
    const val Monero = "monero"
    /** Sprint W2: send XMR to a paired peer. Path: send_xmr/{peerHex}. */
    const val SendXmr = "send_xmr"
    /** Full-screen conversation routes — pushed on top of the
     *  bottom-nav shell so the nav bar is hidden during chat. */
    const val Conversation = "conversation"
    const val Group = "group"
    const val CreateGroup = "create_group"
}

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        if (hi == -1 || lo == -1) return null
        ((hi shl 4) or lo).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
