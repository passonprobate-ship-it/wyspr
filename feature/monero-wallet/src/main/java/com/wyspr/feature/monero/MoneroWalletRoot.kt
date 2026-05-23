package com.wyspr.feature.monero

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.feature.monero.ui.MoneroWalletScreen
import com.wyspr.feature.monero.ui.MoneroWalletViewModel

/**
 * Entry composable for the Monero wallet feature. Wired into
 * `WysprNavHost` as `Routes.Monero`. Holds the ViewModel
 * binding and surfaces the Wallet-tab actions
 * (reveal-seed / restore-from-seed) to the host nav graph so
 * those routes can be pushed on top of the wallet screen.
 */
@Composable
fun MoneroWalletRoot(
    onRevealSeed: () -> Unit = {},
    onRestoreWallet: () -> Unit = {},
    onSweepWallet: () -> Unit = {},
    onOpenTx: (String) -> Unit = {},
    onShowReceive: () -> Unit = {},
    onShowPeerSubaddresses: () -> Unit = {},
    onLearnAboutXmr: () -> Unit = {},
    onSendToAddress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: MoneroWalletViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val seedBackupAcknowledged by viewModel.seedBackupAcknowledged.collectAsStateWithLifecycle()
    MoneroWalletScreen(
        state = state,
        seedBackupAcknowledged = seedBackupAcknowledged,
        onRetry = { viewModel.retry() },
        onRevealSeed = onRevealSeed,
        onRestoreWallet = onRestoreWallet,
        onSweepWallet = onSweepWallet,
        onOpenTx = onOpenTx,
        onShowReceive = onShowReceive,
        onShowPeerSubaddresses = onShowPeerSubaddresses,
        onLearnAboutXmr = onLearnAboutXmr,
        onSendToAddress = onSendToAddress,
        modifier = modifier.fillMaxSize(),
    )
}
