package com.keystone.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.identity.PublicKey
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.PaymentAddressService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@dagger.hilt.android.lifecycle.HiltViewModel
class SendXmrViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
    private val paymentAddressService: PaymentAddressService,
) : ViewModel() {

    data class State(
        val myAddress: String? = null,
        val peerBoundAddress: String? = null,
        val lastSendResult: MoneroWalletService.SendResult? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var peerPub: PublicKey? = null

    /**
     * Bind to a peer — invoked from the screen via LaunchedEffect on
     * navigation. Starts collecting (a) my receive address from the
     * wallet's state, (b) the peer's currently-bound XMR address
     * from the database.
     */
    fun bind(peer: PublicKey) {
        peerPub = peer
        // Bootstrap the wallet — usually already running but cheap to
        // call again (idempotent).
        viewModelScope.launch { walletService.bootstrap() }
        viewModelScope.launch {
            walletService.walletState.collectLatest { ws ->
                val addr = (ws as? MoneroWalletService.WalletState.Ready)?.primaryAddress
                _state.value = _state.value.copy(myAddress = addr)
            }
        }
        viewModelScope.launch {
            paymentAddressService
                .currentForPeerFlow(peer, PaymentAddressService.CHAIN_MONERO)
                .collectLatest { bound ->
                    _state.value = _state.value.copy(peerBoundAddress = bound)
                }
        }
    }

    /** Manual binding action — user pasted the peer's address. */
    fun bindPastedAddress(address: String) {
        val pp = peerPub ?: return
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            paymentAddressService.addForPeer(
                peerPub = pp,
                chain = PaymentAddressService.CHAIN_MONERO,
                address = trimmed,
                notes = "manually bound at " + java.time.Instant.now().toString(),
            )
        }
    }

    /** Build + broadcast the transfer. UI rerenders with the result. */
    fun send(amountAtomicUnits: Long) {
        val pp = peerPub ?: return
        if (amountAtomicUnits <= 0L) return
        viewModelScope.launch {
            val result = walletService.sendTo(pp, amountAtomicUnits)
            _state.value = _state.value.copy(lastSendResult = result)
        }
    }
}
