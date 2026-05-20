package com.keystone.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.feature.monero.MoneroWalletService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Monero wallet screen. v0.7.0a only exposes RPC-derived
 * state — Tor readiness + remote node chain tip + a "wallet engine
 * not bundled" hint. Balance and history are pushed to v0.7.0b.
 */
@HiltViewModel
class MoneroWalletViewModel @Inject constructor(
    private val service: MoneroWalletService,
) : ViewModel() {

    val connection: StateFlow<MoneroWalletService.ConnectionStatus> = service.connection

    init {
        viewModelScope.launch {
            service.awaitTorThenRefresh()
        }
    }

    /** Pull-to-refresh + retry-after-failure entry point. */
    fun refresh() {
        viewModelScope.launch {
            service.refreshNodeInfo()
        }
    }
}
