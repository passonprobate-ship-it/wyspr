package com.wyspr.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.identity.PublicKey
import com.wyspr.feature.monero.MoneroWalletService
import com.wyspr.feature.monero.PaymentAddressService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@dagger.hilt.android.lifecycle.HiltViewModel
class SendXmrViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
    private val paymentAddressService: PaymentAddressService,
    private val database: WysprDatabase,
) : ViewModel() {

    data class State(
        val myAddress: String? = null,
        val peerBoundAddress: String? = null,
        val displayName: String? = null,
        val lastSendResult: MoneroWalletService.SendResult? = null,
        /**
         * Sprint W4: user-selected fee tier for the in-flight
         * compose. Defaults to Medium — same as the engine's
         * default — and persists across recompose. Send passes
         * this through to [MoneroWalletService.sendTo].
         */
        val feePriority: im.molly.monero.sdk.FeePriority =
            im.molly.monero.sdk.FeePriority.Medium,
        /**
         * Sprint W5: per-tier fee estimate in atomic units. Updates
         * as the daemon's fee market shifts. Empty until the wallet
         * has produced its first [DynamicFeeRate] snapshot.
         *
         * Estimate = `feePerByte * AVG_TX_BYTES`. The exact tx
         * size depends on input selection and isn't knowable
         * without building the tx; ~2000 bytes covers a typical
         * 1-in / 2-out RingCT spend to within ±20%.
         */
        val feeEstimateAtomic: Map<im.molly.monero.sdk.FeePriority, Long> = emptyMap(),
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
        viewModelScope.launch {
            walletService.feeRate.collectLatest { rate ->
                val estimate = rate?.feePerByte?.mapValues { (_, amt) ->
                    amt.atomicUnits * AVG_TX_BYTES
                } ?: emptyMap()
                _state.value = _state.value.copy(feeEstimateAtomic = estimate)
            }
        }
        // Best-effort display-name lookup from the contact table.
        // One-shot read — the SendXmr screen is short-lived enough
        // that we don't need a Flow.
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    if (!database.isOpen) database.open()
                    database.contactDao.byPub(peer.bytes)?.displayName
                }.getOrNull()
            }
            _state.value = _state.value.copy(displayName = name)
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
        val priority = _state.value.feePriority
        viewModelScope.launch {
            val result = walletService.sendTo(pp, amountAtomicUnits, priority)
            _state.value = _state.value.copy(lastSendResult = result)
        }
    }

    /** Pick a different fee tier; reflected in the next [send] call. */
    fun setFeePriority(priority: im.molly.monero.sdk.FeePriority) {
        _state.value = _state.value.copy(feePriority = priority)
    }

    private companion object {
        /**
         * Typical Monero RingCT tx size (1 input, 2 outputs).
         * Real txs range ~1500-2500 bytes depending on input
         * selection — multi-input spends can be 2-3x. We use this
         * just to convert fee/byte into a previewable fee/tx so the
         * UX has a number to render; the actual fee at commit time
         * comes from mollyim's `PendingTransfer.fee`.
         */
        const val AVG_TX_BYTES = 2000L
    }
}
