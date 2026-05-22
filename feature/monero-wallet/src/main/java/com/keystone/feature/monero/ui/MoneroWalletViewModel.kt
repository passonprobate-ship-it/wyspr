package com.keystone.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.feature.monero.MoneroWalletService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Monero wallet screen. Bootstraps the underlying
 * [MoneroWalletService] on first ViewModel creation, then renders
 * whatever state mollyim reports — Binding / Ready(balance, address)
 * / Failed. UI is reactive over the single StateFlow.
 */
@HiltViewModel
class MoneroWalletViewModel @Inject constructor(
    private val service: MoneroWalletService,
) : ViewModel() {

    val state: StateFlow<MoneroWalletService.WalletState> = service.walletState

    init {
        viewModelScope.launch { service.bootstrap() }
    }

    /** Retry path for the Failed state. */
    fun retry() {
        viewModelScope.launch { service.bootstrap() }
    }
}
