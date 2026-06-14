package com.wyspr.app

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
import com.wyspr.app.biometric.BiometricUnlocker
import com.wyspr.app.permissions.rememberBlePermissionGate
import com.wyspr.app.profile.EditProfileScreen
import com.wyspr.app.profile.ViewProfileScreen
import com.wyspr.core.identity.GroupId
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.ui.settings.BiometricSettings
import com.wyspr.feature.marketplace.MarketplaceRoot
import com.wyspr.feature.monero.MoneroWalletRoot
import com.wyspr.feature.monero.ui.LearnXmrScreen
import com.wyspr.feature.monero.ui.PeerSubaddressScreen
import com.wyspr.feature.monero.ui.SendToAddressScreen
import com.wyspr.feature.monero.ui.ReceiveXmrScreen
import com.wyspr.feature.monero.ui.RestoreWalletScreen
import com.wyspr.feature.monero.ui.SeedRevealScreen
import com.wyspr.feature.monero.ui.SendXmrScreen
import com.wyspr.feature.monero.ui.SweepWalletScreen
import com.wyspr.feature.monero.ui.TxDetailScreen
import com.wyspr.feature.marketplace.screens.CommunityGraphScreen
import com.wyspr.feature.messaging.mailbox.screens.MailboxScreen
import com.wyspr.feature.messaging.mailbox.screens.ScanMailboxQrScreen
import com.wyspr.feature.messaging.screens.ConversationScreen
import com.wyspr.feature.messaging.screens.CreateGroupScreen
import com.wyspr.feature.messaging.screens.GroupConversationScreen
import com.wyspr.feature.onboarding.OnboardingRoot
import com.wyspr.feature.onboarding.screens.DiscoveryScreen
import com.wyspr.feature.onboarding.screens.ShareApkScreen
import com.wyspr.feature.onboarding.screens.UpdateFromPeerScreen
import com.wyspr.feature.coordination.CoordinationRoot

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
fun WysprNavHost(
    unlocker: BiometricUnlocker,
    biometricSettings: BiometricSettings,
    keyRotationService: KeyRotationService,
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
        when (pendingDeepLink) {
            is MainActivity.DeepLink.OpenChat -> {
                val hex = pendingDeepLink.peerHex
                // Validate: must be exactly 64 hex chars (32-byte pubkey).
                if (hex.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    navController.navigate("${Routes.Conversation}/$hex") {
                        launchSingleTop = true
                    }
                }
                onDeepLinkConsumed()
            }
            is MainActivity.DeepLink.OpenWallet -> {
                navController.navigate(Routes.Monero) {
                    launchSingleTop = true
                }
                onDeepLinkConsumed()
            }
            is MainActivity.DeepLink.OpenEvent -> {
                val hex = pendingDeepLink.eventIdHex
                if (hex.matches(Regex("^[0-9a-fA-F]{2,128}$")) && hex.length % 2 == 0) {
                    navController.navigate("${Routes.Events}?eventId=$hex") {
                        launchSingleTop = true
                    }
                }
                onDeepLinkConsumed()
            }
            null -> { /* nothing pending */ }
        }
    }
    val biometricPrompt: suspend () -> Boolean = remember(activity, unlocker) {
        {
            if (activity == null) true
            else unlocker.authenticate(
                activity = activity,
                title = "Unlock Wyspr identity",
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
                        popUpTo(Routes.Onboarding) { inclusive = true }
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
                onOpenEvents = { navController.navigate(Routes.Events) },
                onOpenFindPeers = { navController.navigate(Routes.PairPeer) },
                onOpenShareApp = { navController.navigate(Routes.ShareApp) },
                onOpenUpdateFromPeer = { navController.navigate(Routes.UpdateFromPeer) },
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
                onIdentityRotate = { keyRotationService.rotate() },
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
        composable(
            route = "${Routes.Events}?eventId={eventId}",
            arguments = listOf(
                navArgument("eventId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            CoordinationRoot(
                onBack = { navController.popBackStack() },
                initialEventIdHex = entry.arguments?.getString("eventId"),
            )
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
            MoneroWalletRoot(
                onRevealSeed = { navController.navigate(Routes.SeedReveal) },
                onRestoreWallet = { navController.navigate(Routes.RestoreWallet) },
                onSweepWallet = { navController.navigate(Routes.SweepWallet) },
                onOpenTx = { hash -> navController.navigate("${Routes.TxDetail}/$hash") },
                onShowReceive = { navController.navigate(Routes.ReceiveXmr) },
                onShowPeerSubaddresses = { navController.navigate(Routes.PeerSubaddresses) },
                onLearnAboutXmr = { navController.navigate(Routes.LearnXmr) },
                onSendToAddress = { navController.navigate(Routes.SendToAddress) },
            )
        }
        composable(Routes.ReceiveXmr) {
            ReceiveXmrScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PeerSubaddresses) {
            PeerSubaddressScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LearnXmr) {
            LearnXmrScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SendToAddress) {
            SendToAddressScreen(
                onBack = { navController.popBackStack() },
                biometricPrompt = biometricPrompt,
            )
        }
        composable(Routes.SweepWallet) {
            SweepWalletScreen(
                onBack = { navController.popBackStack() },
                biometricPrompt = biometricPrompt,
            )
        }
        composable(
            route = "${Routes.TxDetail}/{hash}",
            arguments = listOf(navArgument("hash") { type = NavType.StringType }),
        ) { entry ->
            val hash = entry.arguments?.getString("hash").orEmpty()
            TxDetailScreen(
                txHash = hash,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SeedReveal) {
            SeedRevealScreen(
                onBack = { navController.popBackStack() },
                biometricPrompt = biometricPrompt,
            )
        }
        composable(Routes.RestoreWallet) {
            RestoreWalletScreen(
                onBack = { navController.popBackStack() },
                onRestored = {
                    // Pop back to the wallet root so the user sees
                    // the freshly-restored balance / address.
                    navController.popBackStack(Routes.Monero, inclusive = false)
                },
                biometricPrompt = biometricPrompt,
            )
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
                // Display name is best-effort — SendXmrViewModel
                // looks up the contact name reactively and the
                // screen prefers that over the header arg.
                displayName = null,
                onBack = { navController.popBackStack() },
                biometricPrompt = biometricPrompt,
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
    /** Sprint W4: reveal the 25-word Monero seed (biometric-gated). */
    const val SeedReveal = "seed_reveal"
    /** Sprint W4: restore wallet from 25-word seed or 64-hex spend secret. */
    const val RestoreWallet = "restore_wallet"
    /** Sprint W5: tx detail. Path: tx_detail/{hash}. */
    const val TxDetail = "tx_detail"
    /** Sprint W5: sweep wallet to another address. */
    const val SweepWallet = "sweep_wallet"
    /** Sprint W6: receive-XMR screen with QR code. */
    const val ReceiveXmr = "receive_xmr"
    /** Sprint W6: per-peer subaddress view. */
    const val PeerSubaddresses = "peer_subaddresses"
    /** Sprint W7: "About Monero" explainer + acquisition guide. */
    const val LearnXmr = "learn_xmr"
    /** Sprint W7: send to a typed address with optional address-book save. */
    const val SendToAddress = "send_to_address"
    /** Full-screen conversation routes — pushed on top of the
     *  bottom-nav shell so the nav bar is hidden during chat. */
    const val Conversation = "conversation"
    const val Group = "group"
    const val CreateGroup = "create_group"
    const val Events = "events"
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
