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
import com.keystone.app.home.HomeScreen
import com.keystone.app.permissions.rememberBlePermissionGate
import com.keystone.app.profile.EditProfileScreen
import com.keystone.app.profile.ViewProfileScreen
import com.keystone.core.identity.PublicKey
import com.keystone.core.ui.settings.BiometricSettings
import com.keystone.feature.coordination.CoordinationRoot
import com.keystone.feature.directory.DirectoryRoot
import com.keystone.feature.marketplace.MarketplaceRoot
import com.keystone.feature.marketplace.screens.CommunityGraphScreen
import com.keystone.feature.marketplace.screens.CommunityScreen
import com.keystone.feature.messaging.MessagingRoot
import com.keystone.feature.messaging.mailbox.screens.BeMailboxScreen
import com.keystone.feature.messaging.mailbox.screens.ScanMailboxQrScreen
import com.keystone.feature.messaging.mailbox.screens.UseMailboxScreen
import com.keystone.feature.monero.MoneroWalletRoot
import com.keystone.feature.onboarding.OnboardingRoot
import com.keystone.feature.onboarding.screens.DiscoveryScreen
import com.keystone.feature.onboarding.screens.ShareApkScreen
import com.keystone.feature.onboarding.screens.UpdateFromPeerScreen
import com.keystone.feature.vault.VaultRoot

/**
 * Top-level navigation graph. Feature modules each contribute a
 * sub-graph; the app shell only knows the entry route names.
 *
 * The graph deliberately routes through `onboarding` first and refuses
 * to expose Utility Module routes until a trust edge exists. There is
 * no "skip onboarding" path.
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

    // Honour deep-links posted by the messaging notifier. The
    // pending value re-fires whenever MainActivity emits — both at
    // cold-start (the launching intent) and onNewIntent. After
    // navigating we tell the activity the link was consumed so a
    // rotation doesn't re-trigger.
    var initialChatPeerHex: String? = null
    if (pendingDeepLink is MainActivity.DeepLink.OpenChat) {
        initialChatPeerHex = pendingDeepLink.peerHex
    }
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink is MainActivity.DeepLink.OpenChat) {
            navController.navigate(Routes.Messaging) {
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }
    // The same suspending prompt is used by every screen that needs it;
    // capture once so the lambda identity is stable across recompositions.
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
                // The onboarding flow still calls this hook
                // `onContinueToWallet` by its old name; we now land on
                // the app home menu instead, which is the front door
                // for every working surface (messages, my community,
                // my page, wallet, settings).
                onContinueToWallet = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = false }
                    }
                },
                onFindPeers = { navController.navigate(Routes.Discovery) },
                biometricPrompt = biometricPrompt,
                blePermissionGate = blePermissionGate,
            )
        }
        composable(Routes.Home) {
            HomeScreen(
                onOpenMessages = { navController.navigate(Routes.Messaging) },
                onOpenCommunity = { navController.navigate(Routes.MyCommunity) },
                onOpenMyPage = { navController.navigate(Routes.MyPage) },
                onOpenWallet = { navController.navigate(Routes.Marketplace) },
                onOpenFindPeers = { navController.navigate(Routes.Discovery) },
                onOpenShareApp = { navController.navigate(Routes.ShareApp) },
                onOpenUseMailbox = { navController.navigate(Routes.MailboxUse) },
                onOpenBeMailbox = { navController.navigate(Routes.MailboxBe) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }
        composable(Routes.MailboxUse) {
            UseMailboxScreen(
                onBack = { navController.popBackStack() },
                onScan = { navController.navigate(Routes.MailboxScan) },
            )
        }
        composable(Routes.MailboxBe) {
            BeMailboxScreen(onBack = { navController.popBackStack() })
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
        composable(Routes.MyCommunity) {
            CommunityScreen(
                onBack = { navController.popBackStack() },
                onOpenGraph = { navController.navigate(Routes.MyCommunityGraph) },
            )
        }
        composable(Routes.MyCommunityGraph) {
            CommunityGraphScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Discovery) {
            DiscoveryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Vault) {
            VaultRoot()
        }
        composable(Routes.Marketplace) {
            MarketplaceRoot(
                biometricPrompt = biometricPrompt,
                onIdentityReset = {
                    // Pop the entire back stack so the user can't navigate
                    // back into a now-broken wallet state, then start
                    // fresh at onboarding.
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onOpenMessaging = { navController.navigate(Routes.Messaging) },
            )
        }
        composable(Routes.Messaging) {
            MessagingRoot(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onViewPeerPage = { peerHex ->
                    navController.navigate("${Routes.PeerPage}/$peerHex")
                },
                initialChatPeerHex = initialChatPeerHex,
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
        composable(Routes.Settings) {
            AppSettingsScreen(
                biometricSettings = biometricSettings,
                onOpenAdvanced = { navController.navigate(Routes.Marketplace) },
                onOpenMyPage = { navController.navigate(Routes.MyPage) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MyPage) {
            EditProfileScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Coordination) {
            CoordinationRoot()
        }
        composable(Routes.Directory) {
            DirectoryRoot()
        }
        composable(Routes.Monero) {
            MoneroWalletRoot()
        }
    }
}

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Discovery = "discovery"
    const val Vault = "vault"
    const val Marketplace = "marketplace"
    const val MyCommunity = "my_community"
    const val MyCommunityGraph = "my_community_graph"
    const val Coordination = "coordination"
    const val Directory = "directory"
    const val Messaging = "messaging"
    const val Monero = "monero"
    const val Settings = "settings"
    const val MyPage = "my_page"
    const val PeerPage = "peer_page"
    const val ShareApp = "share_app"
    const val UpdateFromPeer = "update_from_peer"
    const val MailboxUse = "mailbox_use"
    const val MailboxBe = "mailbox_be"
    const val MailboxScan = "mailbox_scan"
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
