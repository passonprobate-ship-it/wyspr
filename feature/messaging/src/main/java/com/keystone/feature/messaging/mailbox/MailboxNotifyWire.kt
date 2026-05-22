package com.keystone.feature.messaging.mailbox

import com.keystone.core.crypto.Cbor

/**
 * Wire format for the mailbox push-notify channel (Sprint 3 of
 * TOR-ACROSS-WEB).
 *
 * Recipients open a long-lived TCP-over-Tor connection to their
 * mailbox host's `.onion:9093` and send a [Subscribe] frame proving
 * ownership of an [MailboxBinding].ownerPub. The host validates the
 * signature, registers the subscriber, and pushes [Notify] frames
 * whenever a new envelope is stored for that ownerPub. The recipient
 * uses those notifications to trigger an immediate sync round
 * instead of waiting for the 8-second auto-sync tick.
 *
 * Why not WebSocket: WebSocket buys nothing here. We already have
 * length-prefixed framing over TCP-over-Tor (mirrors [com.keystone.app.transport.TorLink]),
 * Ed25519 + CBOR for everything else; reinventing those over an
 * HTTP upgrade handshake would just add complexity.
 *
 * Frame envelope on the wire:
 *
 *     [ u16 frame_len BE ][ CBOR payload (frame_len bytes) ]   (max [MAX_FRAME_BYTES])
 *
 * Frame tags chosen so they don't collide with messaging-sync (0-3)
 * or mailbox protocol (10-13). Any received tag the peer doesn't
 * recognise is fatal — drop the connection.
 *
 *   tag=20 → Subscribe(ownerPub: bstr(32), timestampSec: uint,
 *                       signature: bstr(64))
 *     Recipient registers for notifications. Sig is Ed25519 over
 *     `[PROLOGUE_SUBSCRIBE || ownerPub || timestamp_8byte_BE]`.
 *     Timestamp tolerance is [CLOCK_SKEW_SECONDS] either direction.
 *     Sent exactly once per connection, must be the first frame.
 *
 *   tag=21 → Notify
 *     Host poke. No payload in v1; recipient interprets it as "you
 *     have new mail, go pull from this host." Forward-compatible:
 *     CBOR array preserves room for future fields (envelope count,
 *     freshness hint, etc.) without bumping a version.
 */
sealed interface MailboxNotifyFrame {

    fun wireBytes(): ByteArray

    /**
     * Recipient → host. Proves ownership of [ownerPub] by signing
     * `[PROLOGUE_SUBSCRIBE || ownerPub || timestamp_8byte_BE]` with
     * the keystore's identity key. The host validates the signature
     * (Ed25519 verify) before registering the subscription.
     */
    data class Subscribe(
        val ownerPub: ByteArray,
        val timestampSeconds: Long,
        val signature: ByteArray,
    ) : MailboxNotifyFrame {
        init {
            require(ownerPub.size == OWNER_PUB_LENGTH) {
                "ownerPub must be $OWNER_PUB_LENGTH bytes, got ${ownerPub.size}"
            }
            require(signature.size == SIG_LENGTH) {
                "signature must be $SIG_LENGTH bytes, got ${signature.size}"
            }
        }
        override fun wireBytes(): ByteArray = Cbor.Writer()
            .arrayHeader(4)
            .uint(TAG_SUBSCRIBE.toLong())
            .bytes(ownerPub)
            .uint(timestampSeconds)
            .bytes(signature)
            .toByteArray()

        /** Bytes the [signature] field signs over. Stable across versions. */
        fun signedBytes(): ByteArray = signedBytesFor(ownerPub, timestampSeconds)
    }

    /**
     * Host → recipient. "You have new mail." No payload in v1; the
     * recipient triggers a normal mailbox pull on the existing Noise
     * transport. CBOR array shape kept so we can add fields later
     * (envelope count, urgency) without protocol bump.
     */
    data object Notify : MailboxNotifyFrame {
        override fun wireBytes(): ByteArray = Cbor.Writer()
            .arrayHeader(1)
            .uint(TAG_NOTIFY.toLong())
            .toByteArray()
    }

    companion object {
        const val TAG_SUBSCRIBE: Int = 20
        const val TAG_NOTIFY: Int = 21

        const val OWNER_PUB_LENGTH: Int = 32
        const val SIG_LENGTH: Int = 64

        /**
         * Max single-frame payload. Subscribe frames are ~104 bytes,
         * Notify frames are ~3 bytes — this is a safety bound, not
         * a real expected ceiling.
         */
        const val MAX_FRAME_BYTES: Int = 1024

        /** Tolerance for clock skew between recipient and host. */
        const val CLOCK_SKEW_SECONDS: Long = 5 * 60

        /**
         * Prologue mixed into the Subscribe signature input so a
         * signature minted for some OTHER context (a message
         * envelope, a binding cert) can't be replayed as a
         * subscribe authorisation. Stable; do not change.
         */
        val PROLOGUE_SUBSCRIBE: ByteArray =
            "KEYSTONE/v1/mailbox-notify-subscribe".encodeToByteArray()

        /**
         * Canonical bytes the Subscribe signature signs over.
         * `prologue || ownerPub || timestamp (8-byte BE)`.
         */
        fun signedBytesFor(ownerPub: ByteArray, timestampSeconds: Long): ByteArray {
            require(ownerPub.size == OWNER_PUB_LENGTH)
            val out = ByteArray(PROLOGUE_SUBSCRIBE.size + OWNER_PUB_LENGTH + 8)
            PROLOGUE_SUBSCRIBE.copyInto(out, 0)
            ownerPub.copyInto(out, PROLOGUE_SUBSCRIBE.size)
            val tsOffset = PROLOGUE_SUBSCRIBE.size + OWNER_PUB_LENGTH
            for (i in 0 until 8) {
                out[tsOffset + i] = ((timestampSeconds ushr ((7 - i) * 8)) and 0xFF).toByte()
            }
            return out
        }

        /** Decode a CBOR frame. Throws on malformed input or unknown tag. */
        fun fromWire(bytes: ByteArray): MailboxNotifyFrame {
            require(bytes.size <= MAX_FRAME_BYTES) {
                "frame ${bytes.size} > MAX_FRAME_BYTES $MAX_FRAME_BYTES"
            }
            val r = Cbor.Reader(bytes)
            val len = r.arrayHeader()
            require(len >= 1) { "MailboxNotifyFrame: empty array" }
            val tag = r.uint().toInt()
            return when (tag) {
                TAG_SUBSCRIBE -> {
                    require(len == 4) { "Subscribe expects 4 elements, got $len" }
                    val ownerPub = r.bytes()
                    val ts = r.uint()
                    val sig = r.bytes()
                    r.requireEnd()
                    Subscribe(ownerPub, ts, sig)
                }
                TAG_NOTIFY -> {
                    // v1: Notify is array of [tag]. Later versions can
                    // add fields; we accept and ignore them.
                    Notify
                }
                else -> error("MailboxNotifyFrame: unknown tag $tag")
            }
        }
    }
}
