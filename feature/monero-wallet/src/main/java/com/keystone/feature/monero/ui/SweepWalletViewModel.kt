package com.keystone.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.feature.monero.MoneroWalletService
import dagger.hilt.android.lifecycle.HiltViewModel
import im.molly.monero.sdk.FeePriority
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class SweepWalletViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
) : ViewModel() {

    data class State(
        /** Unlocked balance currently spendable in a sweep. */
        val unlockedAtomic: Long = 0L,
        val lastResult: MoneroWalletService.SendResult? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            walletService.walletState.collectLatest { ws ->
                val unlocked = (ws as? MoneroWalletService.WalletState.Ready)
                    ?.confirmedAtomicUnits ?: 0L
                _state.value = _state.value.copy(unlockedAtomic = unlocked)
            }
        }
    }

    fun sweep(recipient: String, feePriority: FeePriority) {
        viewModelScope.launch {
            val result = walletService.sweepAllTo(recipient, feePriority)
            _state.value = _state.value.copy(lastResult = result)
        }
    }
}
