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
 * `KeystoneNavHost` as `Routes.Monero`. Owns nothing but the
 * ViewModel binding — the screen does the rendering.
 */
@Composable
fun MoneroWalletRoot(modifier: Modifier = Modifier) {
    val viewModel: MoneroWalletViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoneroWalletScreen(
        state = state,
        onRetry = { viewModel.retry() },
        modifier = modifier.fillMaxSize(),
    )
}
