package com.wyspr.core.transport

/**
 * Reference-counted foreground-service claim. Modules below the
 * `:app` layer (transports, the Monero wallet, the mailbox host)
 * can claim a slot to keep Android from tearing down their
 * background work; `:app` provides the concrete implementation
 * that toggles the user-visible ongoing notification.
 *
 * Sprint W4: the Monero wallet service claims this while a sync
 * is running so block-refresh continues even when the user
 * backgrounds the Wallet tab. Without it, mollyim's refresh
 * loop sits stalled until the user opens the tab again — which
 * means inbound payments take however-long-the-user-takes-to-look
 * to confirm.
 *
 * Implementations are idempotent: a [start] without a matching
 * [stop] is a leak (the notification persists), but consecutive
 * [start] calls for the same `reason` simply bump a ref count.
 */
interface ForegroundClaim {

    /**
     * Claim a foreground slot for the named [reason]. The reason
     * string is rendered in the user-visible notification copy
     * (e.g. "Wallet syncing in background"). Multiple callers
     * with different reasons coexist — each is its own ref-count
     * slot.
     */
    fun start(reason: Reason)

    /**
     * Release a foreground slot. Must match a prior [start] with
     * the same [reason]. When the underlying ref count drops to
     * zero, the notification is dismissed and the service stops.
     */
    fun stop(reason: Reason)

    enum class Reason {
        /** BLE handshake in progress. */
        Handshake,
        /** Sharing the APK with a peer over HTTPS. */
        Sharing,
        /** General transport (BLE GATT server, Tor hidden service) staying alive. */
        Transport,
        /** Monero wallet sync running in the background. */
        WalletSync,
    }

    /** Test / non-wired fallback. */
    object NoOp : ForegroundClaim {
        override fun start(reason: Reason) {}
        override fun stop(reason: Reason) {}
    }
}
