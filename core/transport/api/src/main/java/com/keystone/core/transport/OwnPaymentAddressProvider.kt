package com.keystone.core.transport

/**
 * Chain-agnostic source of "addresses I want to advertise to my
 * paired peers." `:feature:messaging` consumes this when assembling
 * a sync round's Push frame so the local user's payment addresses
 * propagate alongside mailbox bindings — the recipient stores them
 * via `peer_payment_address` and can then "Send XMR to Alice"
 * without ever copy-pasting.
 *
 * Lives in `core:transport:api` so messaging doesn't have to depend
 * on `feature:monero-wallet`. The real implementation lives in
 * `:feature:monero-wallet` and reads from the live wallet state;
 * a [NoOp] fallback returns empty so feature:messaging works
 * unchanged when the wallet module isn't wired (tests, mailbox-host
 * deployments that don't host a wallet, etc.).
 */
interface OwnPaymentAddressProvider {

    /**
     * Addresses the local user currently wants to share with paired
     * peers. Returned as `(chain, address)` pairs where chain is a
     * string identifier like `"monero"`. Empty list = nothing to
     * advertise this round.
     *
     * Suspending so implementations can query the live wallet
     * state asynchronously. Implementations should be cheap —
     * returning quickly without blocking on I/O matters because
     * this is called inside the sync round's hot path.
     */
    suspend fun ownAddresses(): List<Entry>

    /** One advertisement entry: chain identifier + encoded address. */
    data class Entry(val chain: String, val address: String) {
        init {
            require(chain.isNotBlank()) { "chain must not be blank" }
            require(address.isNotBlank()) { "address must not be blank" }
        }
    }

    /** Empty provider — used in tests and as Hilt's fallback. */
    object NoOp : OwnPaymentAddressProvider {
        override suspend fun ownAddresses(): List<Entry> = emptyList()
    }
}
