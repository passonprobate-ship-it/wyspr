package com.keystone.feature.messaging.mailbox

import com.keystone.core.identity.PublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailboxWireTest {

    private fun envelope(id: Byte = 0x11, createdAt: Long = 1_700_000_000L) = MailboxEnvelope(
        id = ByteArray(MailboxEnvelope.ID_LENGTH) { id },
        toPub = PublicKey(ByteArray(32) { 0x33 }),
        fromPub = PublicKey(ByteArray(32) { 0x44 }),
        createdAt = createdAt,
        ciphertext = ByteArray(64) { 0x55 },
        signature = ByteArray(MailboxEnvelope.SIG_LENGTH) { (it and 0x7F).toByte() },
    )

    @Test
    fun push_roundTrip() {
        val frame = MailboxFrame.Push(envelope())
        val decoded = MailboxFrame.fromWire(frame.wireBytes()) as MailboxFrame.Push
        assertEquals(frame.envelope, decoded.envelope)
    }

    @Test
    fun pull_roundTrip_withCursor() {
        val cursor = MailboxFrame.encodeCursor(123456789L)
        val frame = MailboxFrame.Pull(cursor)
        val decoded = MailboxFrame.fromWire(frame.wireBytes()) as MailboxFrame.Pull
        assertContentEquals(cursor, decoded.sinceCursor)
    }

    @Test
    fun pull_roundTrip_emptyCursor() {
        val frame = MailboxFrame.Pull(MailboxFrame.EMPTY_CURSOR)
        val decoded = MailboxFrame.fromWire(frame.wireBytes()) as MailboxFrame.Pull
        assertEquals(0, decoded.sinceCursor.size)
    }

    @Test
    fun pull_rejectsWrongLengthCursor() {
        // The decoder must reject a 4-byte cursor so a misbehaving peer
        // can't smuggle a malformed cursor that wraps the host's
        // arithmetic. Encode a 4-byte cursor (Pull.wireBytes allows
        // any length); the round-trip decode is where the length check
        // fires.
        val encoded = MailboxFrame.Pull(byteArrayOf(0x01, 0x02, 0x03, 0x04)).wireBytes()
        assertFailsWith<RuntimeException> { MailboxFrame.fromWire(encoded) }
    }

    @Test
    fun pullResponse_roundTrip() {
        val envs = listOf(envelope(id = 0x01), envelope(id = 0x02))
        val cursor = MailboxFrame.encodeCursor(999L)
        val frame = MailboxFrame.PullResponse(envs, cursor)
        val decoded = MailboxFrame.fromWire(frame.wireBytes()) as MailboxFrame.PullResponse
        assertEquals(envs.size, decoded.envelopes.size)
        assertContentEquals(cursor, decoded.maxCursor)
        assertEquals(envs[0], decoded.envelopes[0])
        assertEquals(envs[1], decoded.envelopes[1])
    }

    @Test
    fun pullResponse_emptyBatch() {
        val cursor = MailboxFrame.encodeCursor(0L)
        val frame = MailboxFrame.PullResponse(emptyList(), cursor)
        val decoded = MailboxFrame.fromWire(frame.wireBytes()) as MailboxFrame.PullResponse
        assertEquals(0, decoded.envelopes.size)
        assertContentEquals(cursor, decoded.maxCursor)
    }

    @Test
    fun ack_roundTrip() {
        val ids = listOf(
            ByteArray(MailboxEnvelope.ID_LENGTH) { 0x01 },
            ByteArray(MailboxEnvelope.ID_LENGTH) { 0x02 },
        )
        val frame = MailboxFrame.Ack(ids)
        val decoded = MailboxFrame.fromWire(frame.wireBytes()) as MailboxFrame.Ack
        assertEquals(ids.size, decoded.ids.size)
        assertContentEquals(ids[0], decoded.ids[0])
        assertContentEquals(ids[1], decoded.ids[1])
    }

    @Test
    fun ack_rejectsWrongIdLength() {
        // Manually craft an Ack with a 12-byte id and confirm the decoder
        // refuses it.
        val bogus = com.keystone.core.crypto.Cbor.encode {
            arrayHeader(2)
            uint(MailboxFrame.TAG_ACK.toLong())
            arrayHeader(1)
            bytes(ByteArray(12))
        }
        assertFailsWith<RuntimeException> { MailboxFrame.fromWire(bogus) }
    }

    @Test
    fun end_roundTrip() {
        val decoded = MailboxFrame.fromWire(MailboxFrame.End.wireBytes())
        assertTrue(decoded is MailboxFrame.End)
    }

    @Test
    fun cursor_roundTrip() {
        for (v in listOf(0L, 1L, 0xFFL, 0xFFFFL, 1_700_000_000L, Long.MAX_VALUE)) {
            val enc = MailboxFrame.encodeCursor(v)
            assertEquals(MailboxFrame.CURSOR_LENGTH, enc.size)
            assertEquals(v, MailboxFrame.decodeCursor(enc))
        }
    }

    @Test
    fun fromWire_rejectsUnknownTag() {
        val bogus = com.keystone.core.crypto.Cbor.encode {
            arrayHeader(1)
            uint(99L) // unused tag
        }
        assertFailsWith<RuntimeException> { MailboxFrame.fromWire(bogus) }
    }

    @Test
    fun frame_tags_areStable() {
        // If these tags ever change every deployed mailbox breaks. Pin them.
        assertEquals(10, MailboxFrame.TAG_PUSH)
        assertEquals(11, MailboxFrame.TAG_PULL)
        assertEquals(12, MailboxFrame.TAG_PULL_RESPONSE)
        assertEquals(13, MailboxFrame.TAG_ACK)
        assertEquals(14, MailboxFrame.TAG_END)
    }
}
