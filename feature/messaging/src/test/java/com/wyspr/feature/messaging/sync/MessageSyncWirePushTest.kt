package com.wyspr.feature.messaging.sync

import com.wyspr.core.crypto.Cbor
import com.wyspr.core.identity.PublicKey
import com.wyspr.feature.messaging.MessageEnvelope
import com.wyspr.feature.messaging.mailbox.MailboxBinding
import com.wyspr.feature.messaging.mailbox.MailboxEnvelope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire round-trip tests for the Push frame with mailbox extensions.
 * The Push frame has grown from 2 → 3 → 4 → 5 → 6 elements; the
 * decoder must accept every shape so a peer on an older build stays
 * interoperable while the cluster upgrades.
 *
 * These tests pin the encoding and the back-compat decode path so a
 * future schema bump doesn't silently break deployed mailboxes.
 */
class MessageSyncWirePushTest {

    private fun makeMessageEnvelope(id: Byte = 0x01): MessageEnvelope = MessageEnvelope(
        id = ByteArray(MessageEnvelope.ID_LENGTH) { id },
        fromPub = PublicKey(ByteArray(32) { 0x11 }),
        toPub = PublicKey(ByteArray(32) { 0x22 }),
        createdAt = 1_700_000_000L,
        body = "hello",
        signature = ByteArray(MessageEnvelope.SIG_LENGTH) { (it and 0x7F).toByte() },
    )

    private fun makeMailboxBinding(onion: String? = null): MailboxBinding = MailboxBinding(
        version = MailboxBinding.VERSION,
        ownerPub = PublicKey(ByteArray(32) { 0x33 }),
        mailboxPub = PublicKey(ByteArray(32) { 0x44 }),
        mailboxOnion = onion,
        createdAt = 1_700_000_000L,
        expiresAt = 1_700_000_000L + 90L * 24 * 60 * 60,
        signature = ByteArray(MailboxBinding.SIG_LENGTH) { (it and 0x7F).toByte() },
    )

    private fun makeMailboxEnvelope(id: Byte = 0x55): MailboxEnvelope = MailboxEnvelope(
        id = ByteArray(MailboxEnvelope.ID_LENGTH) { id },
        toPub = PublicKey(ByteArray(32) { 0x66 }),
        fromPub = PublicKey(ByteArray(32) { 0x77 }),
        createdAt = 1_700_000_000L,
        ciphertext = ByteArray(64) { 0x77 },
        signature = ByteArray(MailboxEnvelope.SIG_LENGTH) { (it and 0x7F).toByte() },
    )

    @Test
    fun push_roundTrip_withMailboxBindingsOnly() {
        val binding = makeMailboxBinding(onion = "pmko53q5zuiiocap4wbwb7wne6k34forefrl7ngfls3aipfeoxfbfvqd")
        val push = MessageSyncFrame.Push(
            envelopes = listOf(makeMessageEnvelope()),
            mailboxBindings = listOf(binding),
        )
        val decoded = MessageSyncFrame.fromWire(push.wireBytes()) as MessageSyncFrame.Push
        assertEquals(1, decoded.mailboxBindings.size)
        assertEquals(binding, decoded.mailboxBindings[0])
    }

    @Test
    fun push_roundTrip_withMailboxEnvelopesOnly() {
        val envelope = makeMailboxEnvelope()
        val push = MessageSyncFrame.Push(
            envelopes = emptyList(),
            mailboxEnvelopes = listOf(envelope),
        )
        val decoded = MessageSyncFrame.fromWire(push.wireBytes()) as MessageSyncFrame.Push
        assertEquals(1, decoded.mailboxEnvelopes.size)
        assertEquals(envelope, decoded.mailboxEnvelopes[0])
    }

    @Test
    fun push_roundTrip_withEverything() {
        val push = MessageSyncFrame.Push(
            envelopes = listOf(makeMessageEnvelope(0x01), makeMessageEnvelope(0x02)),
            mailboxBindings = listOf(makeMailboxBinding(), makeMailboxBinding(onion = "x".repeat(56))),
            mailboxEnvelopes = listOf(makeMailboxEnvelope(0x55), makeMailboxEnvelope(0x66)),
        )
        val decoded = MessageSyncFrame.fromWire(push.wireBytes()) as MessageSyncFrame.Push
        assertEquals(2, decoded.envelopes.size)
        assertEquals(2, decoded.mailboxBindings.size)
        assertEquals(2, decoded.mailboxEnvelopes.size)
        assertEquals(push.envelopes[0].id.toList(), decoded.envelopes[0].id.toList())
        assertEquals(push.mailboxBindings[0], decoded.mailboxBindings[0])
        assertEquals(push.mailboxEnvelopes[0], decoded.mailboxEnvelopes[0])
    }

    @Test
    fun push_decode_acceptsLegacy2ElementForm() {
        // Build a 2-element Push by hand (v0.7.2-era encoder).
        val bytes = Cbor.encode {
            arrayHeader(2)
            uint(MessageSyncFrame.TAG_PUSH.toLong())
            arrayHeader(1)
            bytes(makeMessageEnvelope().wireBytes())
        }
        val decoded = MessageSyncFrame.fromWire(bytes) as MessageSyncFrame.Push
        assertEquals(1, decoded.envelopes.size)
        assertEquals(0, decoded.groupEnvelopes.size)
        assertEquals(0, decoded.membershipCerts.size)
        assertEquals(0, decoded.mailboxBindings.size)
        assertEquals(0, decoded.mailboxEnvelopes.size)
    }

    @Test
    fun push_decode_acceptsLegacy4ElementForm() {
        // v0.8-era encoder: envelopes + groups + memberships, no
        // mailbox fields. Confirm the decoder defaults the missing
        // tails to empty lists rather than failing.
        val bytes = Cbor.encode {
            arrayHeader(4)
            uint(MessageSyncFrame.TAG_PUSH.toLong())
            arrayHeader(1)
            bytes(makeMessageEnvelope().wireBytes())
            arrayHeader(0)
            arrayHeader(0)
        }
        val decoded = MessageSyncFrame.fromWire(bytes) as MessageSyncFrame.Push
        assertEquals(1, decoded.envelopes.size)
        assertEquals(0, decoded.mailboxBindings.size)
        assertEquals(0, decoded.mailboxEnvelopes.size)
    }

    @Test
    fun push_encode_emitsCurrent7ElementForm() {
        // Sprint W3: encoder now emits 7 elements (added paymentAddresses).
        // CBOR major type 4 (array) with length 7 == 0x87. Pin the
        // leading byte so a future encoder regression surfaces
        // loudly without needing to walk the rest of the structure.
        val bytes = MessageSyncFrame.Push(envelopes = emptyList()).wireBytes()
        assertEquals(0x87.toByte(), bytes[0])
    }

    @Test
    fun push_emptyPush_stillEncodes() {
        // Engine sends an empty Push every round so the peer's
        // awaitPushAndAck sees something. Confirm the empty form
        // round-trips cleanly.
        val empty = MessageSyncFrame.Push(envelopes = emptyList())
        val decoded = MessageSyncFrame.fromWire(empty.wireBytes()) as MessageSyncFrame.Push
        assertTrue(decoded.envelopes.isEmpty())
        assertTrue(decoded.groupEnvelopes.isEmpty())
        assertTrue(decoded.membershipCerts.isEmpty())
        assertTrue(decoded.mailboxBindings.isEmpty())
        assertTrue(decoded.mailboxEnvelopes.isEmpty())
    }

    @Test
    fun push_preservesMailboxBindingCertBytes_byteExactly() {
        // The receiving end stores `cert_bytes` verbatim for
        // re-broadcast. Confirm the wire round-trip is byte-identical
        // so any future re-broadcast produces the same bytes the
        // owner originally signed.
        val original = makeMailboxBinding(
            onion = "pmko53q5zuiiocap4wbwb7wne6k34forefrl7ngfls3aipfeoxfbfvqd",
        )
        val push = MessageSyncFrame.Push(
            envelopes = emptyList(),
            mailboxBindings = listOf(original),
        )
        val decoded = MessageSyncFrame.fromWire(push.wireBytes()) as MessageSyncFrame.Push
        assertContentEquals(original.wireBytes(), decoded.mailboxBindings[0].wireBytes())
    }
}
