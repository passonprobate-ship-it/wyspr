package com.wyspr.feature.messaging.mailbox

import com.wyspr.core.crypto.Cbor
import com.wyspr.core.identity.PublicKey

/**
 * QR-friendly handoff payload for "I'm a mailbox at this address."
 *
 * The host's "Be a mailbox" screen renders this as a QR. When a peer
 * scans it, their device mints a [MailboxBinding] signed by them
 * pointing at this host, and stores it via [MailboxBindingService.setOwn].
 * The peer then propagates the binding to their trust-graph community
 * in subsequent sync rounds, so anyone wishing to send asynchronous
 * mail to that peer learns the mailbox automatically.
 *
 * Wire form: deterministic CBOR array, base32-wrapped for the QR
 * payload (alphanumeric — QR-friendly + manual-entry friendly).
 *
 *     [ ver: u8 (= 1),
 *       mailboxPub: bstr(32),
 *       onion: bstr(56) | null ]
 *
 * Why so small: the QR has to fit in the camera frame on a phone at
 * arm's length. Three fields keeps the bit count low enough for QR
 * Version 5 (which is comfortably legible).
 */
data class MailboxQr(
    val version: Int,
    val mailboxPub: PublicKey,
    /** Mailbox host's HSv3 .onion (56 base32 chars). Null for BLE-only mailboxes. */
    val onionAddress: String?,
) {
    init {
        require(version == VERSION) { "unsupported MailboxQr version: $version" }
        onionAddress?.let {
            require(it.length == ONION_LENGTH) { "onion address must be $ONION_LENGTH chars" }
        }
    }

    companion object {
        const val VERSION = 1
        const val ONION_LENGTH = 56
    }
}

object MailboxQrCodec {

    fun encode(qr: MailboxQr): ByteArray = Cbor.encode {
        arrayHeader(3)
        uint(MailboxQr.VERSION.toLong())
        bytes(qr.mailboxPub.bytes)
        val onion = qr.onionAddress
        if (onion == null) nullValue() else bytes(onion.toByteArray(Charsets.US_ASCII))
    }

    fun decode(blob: ByteArray): MailboxQr = Cbor.decode(blob) {
        val n = arrayHeader()
        require(n == 3) { "MailboxQr must have 3 fields, got $n" }
        val version = uint().toIntChecked()
        require(version == MailboxQr.VERSION) {
            "unsupported MailboxQr version $version"
        }
        val pub = bytes()
        val onion: String? = bytesOrNull()?.toString(Charsets.US_ASCII)
        MailboxQr(
            version = version,
            mailboxPub = PublicKey(pub),
            onionAddress = onion,
        )
    }

    /**
     * Convenience: encode straight to the alphanumeric base32 form
     * that the QR encoder consumes. Reuses the HandshakeQr codec's
     * proven base32 implementation to avoid divergence.
     */
    fun toBase32(qr: MailboxQr): String =
        com.wyspr.core.trust.HandshakeQrCodec.toBase32(encode(qr))

    /** Inverse — accept base32 input from the scanner. */
    fun fromBase32(text: String): MailboxQr =
        decode(com.wyspr.core.trust.HandshakeQrCodec.fromBase32(text))

    private fun Long.toIntChecked(): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
        return toInt()
    }
}
