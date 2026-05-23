package com.keystone.feature.monero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.core.database.KeystoneDatabase
import com.keystone.feature.monero.MoneroWalletService
import com.keystone.feature.monero.PaymentAddressService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aggregates the per-peer subaddress data the
 * [PeerSubaddressScreen] needs: for each paired peer that has a
 * minted subaddress, show the address + display name + amount
 * received.
 *
 * Data joins:
 *  - `peer_subaddress_mint` provides the (peer, chain, account,
 *    sub, address) mappings.
 *  - `contact` provides the user-supplied display name.
 *  - Wallet state's [MoneroWalletService.WalletState.Ready.receivedBySub]
 *    provides the total received per (account, sub).
 */
@HiltViewModel
class PeerSubaddressViewModel @Inject constructor(
    private val walletService: MoneroWalletService,
    private val database: KeystoneDatabase,
) : ViewModel() {

    data class Row(
        val peerPubFingerprint: String,
        val displayName: String?,
        val address: String,
        val accountIndex: Int,
        val subAddressIndex: Int,
        val receivedAtomic: Long,
    )

    data class State(
        val rows: List<Row> = emptyList(),
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { walletService.bootstrap() }
        viewModelScope.launch {
            walletService.walletState.collectLatest { ws ->
                val received = (ws as? MoneroWalletService.WalletState.Ready)
                    ?.receivedBySub ?: emptyMap()
                val rows = withContext(Dispatchers.IO) {
                    if (!database.isOpen) database.open()
                    val mints = database.peerSubAddressMintDao.all()
                        .filter { it.chain == PaymentAddressService.CHAIN_MONERO }
                    mints.map { mint ->
                        val name = database.contactDao.byPub(mint.peerPub)?.displayName
                        Row(
                            peerPubFingerprint = mint.peerPub.toFingerprint(),
                            displayName = name,
                            address = mint.address,
                            accountIndex = mint.accountIndex,
                            subAddressIndex = mint.subAddressIndex,
                            receivedAtomic = received[
                                MoneroWalletService.SubAddrKey(
                                    accountIndex = mint.accountIndex,
                                    subAddressIndex = mint.subAddressIndex,
                                ),
                            ] ?: 0L,
                        )
                    }.sortedByDescending { it.receivedAtomic }
                }
                _state.value = State(rows = rows, loading = false)
            }
        }
    }

    private fun ByteArray.toFingerprint(): String =
        joinToString("") { "%02x".format(it) }.take(20)
}
