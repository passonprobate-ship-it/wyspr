package com.keystone.core.transport

/**
 * Platform facade that the messaging sync uses to surface "new
 * message from peer X" to the user via the OS notification shade.
 * Lives in `core:transport:api` (next to [TransportLifecycle]) so
 * feature modules can call it without depending on `:app`; the
 * `:app` module binds the real Android implementation via Hilt.
 *
 * Implementations should coalesce by peer — one notification per
 * peer, updated in place when more messages arrive from the same
 * peer before the user opens the chat. Tap-behaviour is up to the
 * impl; typically returns the user to MainActivity.
 */
interface MessagingNotifier {

    /**
     * Notify the user that [count] new messages arrived from the
     * peer identified by [peerPub] (the 32-byte Ed25519 identity).
     * [senderFingerprint] is the human-readable 5-group base32
     * form for the header line; [preview] is the body of the most
     * recent message, truncated to fit a notification line by the
     * implementation.
     */
    fun notifyInbound(
        peerPub: ByteArray,
        senderFingerprint: String,
        count: Int,
        preview: String,
    )

    /** Clear any pending notifications for [peerPub] (e.g. the user opened the chat). */
    fun clearForPeer(peerPub: ByteArray)

    /** No-op fallback for tests + early bootstrap. */
    object NoOp : MessagingNotifier {
        override fun notifyInbound(
            peerPub: ByteArray,
            senderFingerprint: String,
            count: Int,
            preview: String,
        ) = Unit
        override fun clearForPeer(peerPub: ByteArray) = Unit
    }
}
