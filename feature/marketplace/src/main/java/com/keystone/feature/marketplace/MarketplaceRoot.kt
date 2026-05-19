package com.keystone.feature.marketplace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
// biometric prompt parameter is threaded from the outside (MainActivity)
import com.keystone.feature.marketplace.screens.AuditScreen
import com.keystone.feature.marketplace.screens.CommunityGraphScreen
import com.keystone.feature.marketplace.screens.CommunityScreen
import com.keystone.feature.marketplace.screens.HistoryScreen
import com.keystone.feature.marketplace.screens.HomeScreen
import com.keystone.feature.marketplace.screens.ReceiveScreen
import com.keystone.feature.marketplace.screens.SendScreen
import com.keystone.feature.marketplace.screens.SettingsScreen

/**
 * The wallet feature's entry composable. Owns its own NavController so
 * the top-level shell can mount us at a single route and we manage our
 * own internal screens.
 */
@Composable
fun MarketplaceRoot(
    viewModel: WalletViewModel = hiltViewModel(),
    biometricPrompt: suspend () -> Boolean = { true },
    onIdentityReset: () -> Unit = {},
    onOpenMessaging: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sendResult by viewModel.sendResult.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricGateEnabled.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = WalletRoutes.Home) {
        composable(WalletRoutes.Home) {
            HomeScreen(
                state = state,
                onSend = { nav.navigate(WalletRoutes.Send) },
                onReceive = { nav.navigate(WalletRoutes.Receive) },
                onHistory = { nav.navigate(WalletRoutes.History) },
                onAudit = { nav.navigate(WalletRoutes.Audit) },
                onSettings = { nav.navigate(WalletRoutes.Settings) },
                onCommunity = { nav.navigate(WalletRoutes.Community) },
                onMessages = onOpenMessaging,
                onMintDebug = { amount -> viewModel.mintDebugGenesis(amount, biometricPrompt) },
            )
        }
        composable(WalletRoutes.Community) {
            CommunityScreen(
                onBack = { nav.popBackStack() },
                onOpenGraph = { nav.navigate(WalletRoutes.CommunityGraph) },
            )
        }
        composable(WalletRoutes.CommunityGraph) {
            CommunityGraphScreen(onBack = { nav.popBackStack() })
        }
        composable(WalletRoutes.Send) {
            SendScreen(
                state = state,
                sendResult = sendResult,
                onSend = { hex, amount, memo -> viewModel.send(hex, amount, memo, biometricPrompt) },
                onAcknowledge = viewModel::acknowledgeSendResult,
                onBack = { nav.popBackStack() },
            )
        }
        composable(WalletRoutes.Receive) {
            ReceiveScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(WalletRoutes.History) {
            HistoryScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(WalletRoutes.Audit) {
            AuditScreen(
                state = state,
                onRunAudit = viewModel::runAudit,
                onBack = { nav.popBackStack() },
            )
        }
        composable(WalletRoutes.Settings) {
            val bindRequested by viewModel.bindIdentityToBiometric.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var switchFeedback by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<String?>(null)
            }
            SettingsScreen(
                biometricGateEnabled = biometricEnabled,
                onBiometricGateChange = viewModel::setBiometricGate,
                bindIdentityRequested = bindRequested,
                onBindIdentityRequestedChange = viewModel::setBindIdentityToBiometric,
                identityIsBound = viewModel.identityIsBound,
                activeCommunityHex = viewModel.activeCommunityHex,
                onSwitchCommunity = { hex ->
                    scope.launch {
                        switchFeedback = if (viewModel.switchCommunity(hex)) null
                        else "Invalid community ID — must be 64 hex characters."
                    }
                },
                switchCommunityFeedback = switchFeedback,
                onResetIdentity = {
                    scope.launch {
                        val ok = viewModel.resetIdentity()
                        if (ok) onIdentityReset()
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

private object WalletRoutes {
    const val Home = "wallet_home"
    const val Send = "wallet_send"
    const val Receive = "wallet_receive"
    const val History = "wallet_history"
    const val Audit = "wallet_audit"
    const val Settings = "wallet_settings"
    const val Community = "wallet_community"
    const val CommunityGraph = "wallet_community_graph"
}
