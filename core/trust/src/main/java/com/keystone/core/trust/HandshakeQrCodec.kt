package com.keystone.core.trust

import com.keystone.core.crypto.Cbor
import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey

/**
 * Canonical wire format for [HandshakeQr]. PROTOCOLS.md §3.1.
 *
 * Encoded as a deterministic CBOR array of six fields, in this order:
 *
 *     [ ver: u8,
 *       community: bstr(32),
 *       identity: bstr(32),
 *       ephemeral: bstr(32),
 *       nonce: bstr(16),
 *       mintedAt: u64 ]
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

    private const val FIELD_COUNT = 6

    fun encode(qr: HandshakeQr): ByteArray = Cbor.encode {
        arrayHeader(FIELD_COUNT)
        uint(qr.version.toLong())
        bytes(qr.communityId.bytes)
        bytes(qr.identityPub.bytes)
        bytes(qr.ephemeralPub)
        bytes(qr.nonce)
        uint(qr.mintedAt)
    }

    fun decode(blob: ByteArray): HandshakeQr = Cbor.decode(blob) {
        val n = arrayHeader()
        require(n == FIELD_COUNT) {
            "HandshakeQr must have $FIELD_COUNT fields, got $n"
        }
        val version = uint().toIntChecked()
        val community = bytes()
        val identity = bytes()
        val ephemeral = bytes()
        val nonce = bytes()
        val mintedAt = uint()
        HandshakeQr(
            version = version,
            communityId = CommunityId(community),
            identityPub = PublicKey(identity),
            ephemeralPub = ephemeral,
            nonce = nonce,
            mintedAt = mintedAt,
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
