package com.wyspr.core.trust

import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HandshakeQrCodecTest {

    private fun sample(
        ephemeral: ByteArray = ByteArray(32) { 0x55 },
        nonce: ByteArray = ByteArray(16) { 0x66 },
        mintedAt: Long = 1_700_000_000L,
        onionAddress: String? = null,
    ) = HandshakeQr(
        version = HandshakeQr.VERSION,
        communityId = CommunityId(ByteArray(32) { 0xAA.toByte() }),
        identityPub = PublicKey(ByteArray(32) { 0xBB.toByte() }),
        ephemeralPub = ephemeral,
        nonce = nonce,
        mintedAt = mintedAt,
        onionAddress = onionAddress,
    )

    /** A canonical-shaped HSv3 address (56 lowercase base32 chars). */
    private val sampleOnion =
        "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx"

    @Test fun round_trip_via_cbor_without_onion() {
        val qr = sample()
        val encoded = HandshakeQrCodec.encode(qr)
        val decoded = HandshakeQrCodec.decode(encoded)
        assertEquals(qr.version, decoded.version)
        assertContentEquals(qr.communityId.bytes, decoded.communityId.bytes)
        assertContentEquals(qr.identityPub.bytes, decoded.identityPub.bytes)
        assertContentEquals(qr.ephemeralPub, decoded.ephemeralPub)
        assertContentEquals(qr.nonce, decoded.nonce)
        assertEquals(qr.mintedAt, decoded.mintedAt)
        assertNull(decoded.onionAddress)
    }

    @Test fun round_trip_via_cbor_with_onion() {
        val qr = sample(onionAddress = sampleOnion)
        val decoded = HandshakeQrCodec.decode(HandshakeQrCodec.encode(qr))
        assertEquals(qr, decoded)
        assertEquals(sampleOnion, decoded.onionAddress)
    }

    @Test fun encode_is_deterministic_with_onion() {
        val a = HandshakeQrCodec.encode(sample(onionAddress = sampleOnion))
        val b = HandshakeQrCodec.encode(sample(onionAddress = sampleOnion))
        assertContentEquals(a, b)
    }

    @Test fun onion_changes_the_encoded_bytes() {
        // Defensive sanity check: an empty-vs-set onion field must
        // produce different bytes — otherwise a downgrade-to-null
        // bug would silently throw away the address.
        val withOnion = HandshakeQrCodec.encode(sample(onionAddress = sampleOnion))
        val withoutOnion = HandshakeQrCodec.encode(sample(onionAddress = null))
        assertNotEquals(
            withOnion.toList(),
            withoutOnion.toList(),
            "encoding must distinguish onion=set from onion=null",
        )
    }

    @Test fun round_trip_via_base32() {
        val qr = sample(onionAddress = sampleOnion)
        val encoded = HandshakeQrCodec.encode(qr)
        val text = HandshakeQrCodec.toBase32(encoded)
        val backToBytes = HandshakeQrCodec.fromBase32(text)
        assertContentEquals(encoded, backToBytes)
    }

    @Test fun base32_ignores_whitespace_and_padding() {
        val qr = sample(onionAddress = sampleOnion)
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

    @Test fun decode_rejects_v1_array_with_version_2() {
        // 6-field array, but the version byte claims v2. The decoder
        // pins field count to the declared version, so the mismatch
        // must fail at the assertion rather than read garbage.
        // Re-encode a v1-shaped payload by hand using the same Cbor
        // primitives the encoder uses.
        val faked = com.wyspr.core.crypto.Cbor.encode {
            arrayHeader(6)
            uint(2L) // claims v2
            bytes(ByteArray(32) { 0xAA.toByte() })
            bytes(ByteArray(32) { 0xBB.toByte() })
            bytes(ByteArray(32) { 0x55.toByte() })
            bytes(ByteArray(16) { 0x66.toByte() })
            uint(1_700_000_000L)
            // no 7th field
        }
        assertFailsWith<IllegalArgumentException> {
            HandshakeQrCodec.decode(faked)
        }
    }

    @Test fun encoded_size_is_around_165_bytes_with_onion() {
        // With onion: 1 (array hdr) + 1 (ver) + 34 (community) +
        // 34 (identity) + 34 (ephemeral) + 17 (nonce) + 5 (mintedAt
        // u32-follow) + 2 (bstr hdr + len) + 56 (onion) = 184 bytes
        // ballpark. Exact number is asserted to catch unintentional
        // shape drift.
        val size = HandshakeQrCodec.encode(sample(onionAddress = sampleOnion)).size
        assertEquals(184, size)
    }

    @Test fun encoded_size_is_around_127_bytes_without_onion() {
        // Without onion: same as above, minus the 56-byte address but
        // plus a 1-byte CBOR null. So 184 - 56 - 2 + 1 = 127.
        val size = HandshakeQrCodec.encode(sample()).size
        assertEquals(127, size)
    }
}
