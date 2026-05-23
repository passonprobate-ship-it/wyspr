package com.wyspr.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyspr.core.database.entities.AddressBookEntity
import com.wyspr.feature.monero.AddressBookService
import com.wyspr.feature.monero.MoneroWalletService
import com.wyspr.feature.monero.PaymentAddressService
import dagger.hilt.android.lifecycle.HiltViewModel
import im.molly.monero.sdk.FeePriority
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class SendToAddressViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
    private val addressBook: AddressBookService,
) : ViewModel() {

    data class State(
        val book: List<AddressBookEntity> = emptyList(),
        val lastResult: MoneroWalletService.SendResult? = null,
        val feeEstimateAtomic: Map<FeePriority, Long> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { walletService.bootstrap() }
        viewModelScope.launch {
            addressBook.forChainFlow(PaymentAddressService.CHAIN_MONERO).collectLatest { rows ->
                _state.value = _state.value.copy(book = rows)
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
    }

    /**
     * Build + broadcast. If [saveLabel] is non-blank, also save
     * the (label, address) entry to the address book so the user
     * can reach it without re-pasting next time. Save happens
     * regardless of send success — the address being typed is
     * what the user wants remembered.
     */
    fun send(
        address: String,
        amountAtomicUnits: Long,
        feePriority: FeePriority,
        saveLabel: String? = null,
    ) {
        if (amountAtomicUnits <= 0L) return
        viewModelScope.launch {
            saveLabel?.takeIf { it.isNotBlank() }?.let { label ->
                addressBook.upsert(
                    chain = PaymentAddressService.CHAIN_MONERO,
                    label = label,
                    address = address,
                )
            }
            val result = walletService.sendToAddress(address, amountAtomicUnits, feePriority)
            _state.value = _state.value.copy(lastResult = result)
            if (result is MoneroWalletService.SendResult.Sent && saveLabel != null) {
                // Touch on success so the entry sorts to top next time.
                addressBook.touch(PaymentAddressService.CHAIN_MONERO, saveLabel)
            }
        }
    }

    fun deleteFromBook(label: String) {
        viewModelScope.launch {
            addressBook.delete(PaymentAddressService.CHAIN_MONERO, label)
        }
    }

    private companion object {
        const val AVG_TX_BYTES = 2000L
    }
}
