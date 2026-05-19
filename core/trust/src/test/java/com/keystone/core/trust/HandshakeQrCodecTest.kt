package com.keystone.core.trust

import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HandshakeQrCodecTest {

    private fun sample(
        ephemeral: ByteArray = ByteArray(32) { 0x55 },
        nonce: ByteArray = ByteArray(16) { 0x66 },
        mintedAt: Long = 1_700_000_000L,
    ) = HandshakeQr(
        version = HandshakeQr.VERSION,
        communityId = CommunityId(ByteArray(32) { 0xAA.toByte() }),
        identityPub = PublicKey(ByteArray(32) { 0xBB.toByte() }),
        ephemeralPub = ephemeral,
        nonce = nonce,
        mintedAt = mintedAt,
    )

    @Test fun round_trip_via_cbor() {
        val qr = sample()
        val encoded = HandshakeQrCodec.encode(qr)
        val decoded = HandshakeQrCodec.decode(encoded)
        assertEquals(qr.version, decoded.version)
        assertContentEquals(qr.communityId.bytes, decoded.communityId.bytes)
        assertContentEquals(qr.identityPub.bytes, decoded.identityPub.bytes)
        assertContentEquals(qr.ephemeralPub, decoded.ephemeralPub)
        assertContentEquals(qr.nonce, decoded.nonce)
        assertEquals(qr.mintedAt, decoded.mintedAt)
    }

    @Test fun encode_is_deterministic() {
        val a = HandshakeQrCodec.encode(sample())
        val b = HandshakeQrCodec.encode(sample())
        assertContentEquals(a, b)
    }

    @Test fun round_trip_via_base32() {
        val qr = sample()
        val encoded = HandshakeQrCodec.encode(qr)
        val text = HandshakeQrCodec.toBase32(encoded)
        val backToBytes = HandshakeQrCodec.fromBase32(text)
        assertContentEquals(encoded, backToBytes)
    }

    @Test fun base32_ignores_whitespace_and_padding() {
        val qr = sample()
        val text = HandshakeQrCodec.toBase32(HandshakeQrCodec.encode(qr))
        // Insert spaces, newlines, lowercase, padding chars — all should be tolerated.
        val mangled = text.toCharArray().joinToString(separator = " ").lowercase() + "==="
        val backToBytes = HandshakeQrCodec.fromBase32(mangled)
        assertEquals(qr, HandshakeQrCodec.decode(backToBytes))
    }

    @Test fun decode_rejects_truncated_bytes() {
        val full = HandshakeQrCodec.encode(sample())
        assertFailsWith<IllegalArgumentException> {
            HandshakeQrCodec.decode(full.copyOf(full.size - 1))
        }
    }

    @Test fun decode_rejects_wrong_field_count() {
        // Build a 5-element CBOR array to force the wrong field count.
        val tooFew = byteArrayOf(0x85.toByte()) +
            byteArrayOf(0x01) + ByteArray(0)
        assertFailsWith<IllegalArgumentException> {
            HandshakeQrCodec.decode(tooFew)
        }
    }

    @Test fun encoded_size_is_around_120_bytes() {
        // The CBOR form is slightly larger than the legacy fixed-width 121,
        // but bounded by the field sizes plus their length headers.
        val size = HandshakeQrCodec.encode(sample()).size
        // 1 (array hdr) + 1 (ver) + 34 (community) + 34 (identity) +
        // 34 (ephemeral) + 17 (nonce) + 5 (mintedAt u32-follow) = 126
        assertEquals(126, size)
    }
}
