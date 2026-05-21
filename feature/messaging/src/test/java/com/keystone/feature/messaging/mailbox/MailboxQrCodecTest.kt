package com.keystone.feature.messaging.mailbox

import com.keystone.core.identity.PublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MailboxQrCodecTest {

    private fun sample(onion: String? = "pmko53q5zuiiocap4wbwb7wne6k34forefrl7ngfls3aipfeoxfbfvqd") =
        MailboxQr(
            version = MailboxQr.VERSION,
            mailboxPub = PublicKey(ByteArray(32) { 0x11 }),
            onionAddress = onion,
        )

    @Test
    fun encode_decode_roundTrip_withOnion() {
        val qr = sample()
        val decoded = MailboxQrCodec.decode(MailboxQrCodec.encode(qr))
        assertEquals(qr.version, decoded.version)
        assertContentEquals(qr.mailboxPub.bytes, decoded.mailboxPub.bytes)
        assertEquals(qr.onionAddress, decoded.onionAddress)
    }

    @Test
    fun encode_decode_roundTrip_withNullOnion() {
        val qr = sample(onion = null)
        val decoded = MailboxQrCodec.decode(MailboxQrCodec.encode(qr))
        assertNull(decoded.onionAddress)
    }

    @Test
    fun base32_roundTrip() {
        val qr = sample()
        val text = MailboxQrCodec.toBase32(qr)
        // Base32 alphabet is alphanumeric — sanity-check the encoder.
        for (c in text) {
            assert(c in '0'..'9' || c in 'A'..'Z') { "non-base32 char: $c" }
        }
        val decoded = MailboxQrCodec.fromBase32(text)
        assertContentEquals(qr.mailboxPub.bytes, decoded.mailboxPub.bytes)
        assertEquals(qr.onionAddress, decoded.onionAddress)
    }

    @Test
    fun rejectsBadOnionLength() {
        assertFailsWith<IllegalArgumentException> {
            MailboxQr(version = MailboxQr.VERSION, mailboxPub = PublicKey(ByteArray(32)), onionAddress = "tooshort")
        }
    }

    @Test
    fun rejectsUnknownVersion() {
        // Manually craft a v2 payload that the v1 decoder must refuse.
        val bytes = com.keystone.core.crypto.Cbor.encode {
            arrayHeader(3)
            uint(99L)
            bytes(ByteArray(32))
            nullValue()
        }
        assertFailsWith<RuntimeException> { MailboxQrCodec.decode(bytes) }
    }
}
