package com.wyspr.feature.monero

import com.wyspr.core.transport.OwnPaymentAddressProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OwnPaymentAddressProvider] backed by the live wallet state.
 *
 * Two code paths:
 *
 *  - [ownAddressesFor] (Sprint W4): given a known peerPub, mints a
 *    relationship-scoped subaddress via [SubAddressMintService] and
 *    advertises it. The peerPub → (account, sub) mapping is cached
 *    in `peer_subaddress_mint` so the same peer sees the same
 *    address across rounds, but different peers see different
 *    addresses. Improves chain-side privacy — observers can't
 *    link payments received from peer A and peer B to the same
 *    wallet.
 *  - [ownAddresses] (fallback): when no peer context is available
 *    (e.g. callers that haven't migrated to the per-peer form yet)
 *    advertise the primary address. This is a privacy regression
 *    vs. subaddress-per-peer, but the messaging sync engine always
 *    has a peer in hand so it uses the per-peer form in practice.
 *
 * Either form returns an empty list when the wallet isn't open
 * (cold start before the user has visited the Wallet tab); peers
 * pick up the address on the next round after bootstrap.
 */
@Singleton
class MoneroOwnPaymentAddressProvider @Inject constructor(
    private val walletService: MoneroWalletService,
    private val subAddressMintService: SubAddressMintService,
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

    override suspend fun ownAddressesFor(
        peerPub: ByteArray,
    ): List<OwnPaymentAddressProvider.Entry> {
        // Wallet must be open to mint. Empty list fast-paths the
        // cold-start case: peers pick up the address on the next
        // round after bootstrap.
        if (walletService.walletState.value !is MoneroWalletService.WalletState.Ready) {
            return emptyList()
        }
        val sub = subAddressMintService.mintFor(peerPub) ?: return emptyList()
        return listOf(
            OwnPaymentAddressProvider.Entry(
                chain = PaymentAddressService.CHAIN_MONERO,
                address = sub,
            ),
        )
    }
}
