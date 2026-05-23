package com.keystone.feature.monero

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.feature.monero.ui.MoneroWalletScreen
import com.keystone.feature.monero.ui.MoneroWalletViewModel

/**
 * Entry composable for the Monero wallet feature. Wired into
 * `KeystoneNavHost` as `Routes.Monero`. Holds the ViewModel
 * binding and surfaces the Wallet-tab actions
 * (reveal-seed / restore-from-seed) to the host nav graph so
 * those routes can be pushed on top of the wallet screen.
 */
@Composable
fun MoneroWalletRoot(
    onRevealSeed: () -> Unit = {},
    onRestoreWallet: () -> Unit = {},
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
        modifier = modifier.fillMaxSize(),
    )
}
