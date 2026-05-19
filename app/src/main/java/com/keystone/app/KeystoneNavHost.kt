package com.keystone.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.keystone.app.biometric.BiometricUnlocker
import com.keystone.app.permissions.rememberBlePermissionGate
import com.keystone.feature.marketplace.MarketplaceRoot
import com.keystone.feature.messaging.MessagingRoot
import com.keystone.feature.onboarding.OnboardingRoot
import com.keystone.feature.onboarding.screens.DiscoveryScreen

/**
 * Top-level navigation graph. Feature modules each contribute a
 * sub-graph; the app shell only knows the entry route names.
 *
 * The graph deliberately routes through `onboarding` first and refuses
 * to expose Utility Module routes until a trust edge exists. There is
 * no "skip onboarding" path.
 */
@Composable
fun KeystoneNavHost(unlocker: BiometricUnlocker) {
    val navController = rememberNavController()
    val activity = LocalContext.current as? FragmentActivity
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
                onContinueToWallet = {
                    navController.navigate(Routes.Marketplace) {
                        popUpTo(Routes.Onboarding) { inclusive = false }
                    }
                },
                onFindPeers = { navController.navigate(Routes.Discovery) },
                biometricPrompt = biometricPrompt,
                blePermissionGate = blePermissionGate,
            )
        }
        composable(Routes.Discovery) {
            DiscoveryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Vault) {
            // TODO: feature:vault entry composable
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
            MessagingRoot(onBack = { navController.popBackStack() })
        }
        composable(Routes.Coordination) {
            // TODO: feature:coordination entry composable
        }
        composable(Routes.Directory) {
            // TODO: feature:directory entry composable
        }
    }
}

object Routes {
    const val Onboarding = "onboarding"
    const val Discovery = "discovery"
    const val Vault = "vault"
    const val Marketplace = "marketplace"
    const val Coordination = "coordination"
    const val Directory = "directory"
    const val Messaging = "messaging"
}
