package com.keystone.feature.monero

import com.keystone.core.transport.OwnPaymentAddressProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OwnPaymentAddressProvider] backed by the live wallet state. When
 * the wallet is open and ready, advertises the primary Monero
 * receive address to paired peers via the messaging sync round's
 * Push frame. When the wallet isn't yet bootstrapped (cold start,
 * before the user has visited the Wallet tab) the list is empty —
 * peers get the address on the next round after bootstrap.
 *
 * Sprint W3 hardening to land: per-peer subaddress minting
 * (`MoneroWallet.findUnusedSubAddress`) so each peer gets a
 * relationship-scoped address that's unlinkable from other peers'
 * on-chain. For v1 we share the primary address — privacy
 * regression vs. subaddress-per-peer, gain in simplicity.
 */
@Singleton
class MoneroOwnPaymentAddressProvider @Inject constructor(
    private val walletService: MoneroWalletService,
) : OwnPaymentAddressProvider {

    override suspend fun ownAddresses(): List<OwnPaymentAddressProvider.Entry> {
        val state = walletService.walletState.value
        val address = (state as? MoneroWalletService.WalletState.Ready)?.primaryAddress
            ?: return emptyList()
        return listOf(
            OwnPaymentAddressProvider.Entry(
                chain = PaymentAddressService.CHAIN_MONERO,
                address = address,
            ),
        )
    }
}
