package com.wyspr.core.trust

import com.wyspr.core.crypto.Cbor
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey

/**
 * Canonical wire format for [HandshakeQr]. PROTOCOLS.md §3.1.
 *
 * Wire version 2 (current — Sprint 3 onwards): deterministic CBOR array
 * of seven fields, in this order:
 *
 *     [ ver: u8 (= 2),
 *       community: bstr(32),
 *       identity: bstr(32),
 *       ephemeral: bstr(32),
 *       nonce: bstr(16),
 *       mintedAt: u64,
 *       onion: bstr(56) | null ]
 *
 * Wire version 1 (legacy, decode-only): six fields without the trailing
 * `onion`. Encoding always emits v2 — the QR is a fresh artifact every
 * 5 minutes, so there are no v1 emitters to keep working. v1 decode is
 * kept for the short window where two installs at different patch
 * levels might still pair before everyone has updated.
 *
 * The QR carries the CBOR bytes wrapped in RFC 4648 base32 (no padding)
 * so the resulting payload is alphanumeric — friendlier to the QR
 * encoder's segmentation and to manual entry as a fallback.
 *
 * Reusing the shared [Cbor] codec means QR encoding and Gem envelope
 * encoding share the same byte-stability guarantees: the same input
 * produces the same bytes, and any deviation (non-shortest integer,
 * indefinite-length item, etc.) is rejected on decode.
 */
object HandshakeQrCodec {

    private const val FIELD_COUNT_V1 = 6
    private const val FIELD_COUNT_V2 = 7

    fun encode(qr: HandshakeQr): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT_V2)
        uint(HandshakeQr.VERSION.toLong())
        bytes(qr.communityId.bytes)
        bytes(qr.identityPub.bytes)
        bytes(qr.ephemeralPub)
        bytes(qr.nonce)
        uint(qr.mintedAt)
        val onion = qr.onionAddress
        if (onion == null) nullValue() else bytes(onion.toByteArray(Charsets.US_ASCII))
    }

    fun decode(blob: ByteArray): HandshakeQr = Cbor.decode(blob) {
        val n = arrayHeader()
        require(n == FIELD_COUNT_V1 || n == FIELD_COUNT_V2) {
            "HandshakeQr must have $FIELD_COUNT_V1 or $FIELD_COUNT_V2 fields, got $n"
        }
        val version = uint().toIntChecked()
        require(version in HandshakeQr.MIN_VERSION..HandshakeQr.VERSION) {
            "unsupported QR version $version (this build understands " +
                "${HandshakeQr.MIN_VERSION}..${HandshakeQr.VERSION})"
        }
        // Defence in depth: a v1 array masquerading as version=2 (or
        // vice versa) would otherwise read past the end / leave bytes
        // unconsumed. Pinning the field-count expectation to the
        // declared version catches truncated or padded payloads early.
        val expectedFields = if (version == 1) FIELD_COUNT_V1 else FIELD_COUNT_V2
        require(n == expectedFields) {
            "v$version QR must have $expectedFields fields, got $n"
        }
        val community = bytes()
        val identity = bytes()
        val ephemeral = bytes()
        val nonce = bytes()
        val mintedAt = uint()
        val onion: String? = if (version >= 2) {
            bytesOrNull()?.toString(Charsets.US_ASCII)
        } else {
            null
        }
        HandshakeQr(
            version = version,
            communityId = CommunityId(community),
            identityPub = PublicKey(identity),
            ephemeralPub = ephemeral,
            nonce = nonce,
            mintedAt = mintedAt,
            onionAddress = onion,
        )
    }

    /** RFC 4648 base32 alphabet, no padding. */
    fun toBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(alphabet[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun fromBase32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = text.uppercase()
            .filter { it != ' ' && it != '\n' && it != '\r' && it != '=' }
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "non-base32 char '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[pos++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return if (pos == out.size) out else out.copyOf(pos)
    }

    private fun Long.toIntChecked(): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
        return toInt()
    }
}
