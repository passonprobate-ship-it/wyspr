package com.wyspr.feature.messaging.mailbox

import com.wyspr.core.identity.PublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Round-trip + invariant tests for the [MailboxEnvelope] and
 * [MailboxBinding] CBOR codecs. No native libraries are exercised —
 * libsodium calls (seal/unseal/sign/verify) belong in an
 * instrumentation test against a real device. These tests pin the
 * canonical encoding so future schema bumps don't silently break
 * deployed mailboxes.
 */
class MailboxEnvelopeCodecTest {

    // ---- MailboxEnvelope ----

    private fun sampleEnvelope(
        id: ByteArray = ByteArray(MailboxEnvelope.ID_LENGTH) { 0x11 },
        ciphertext: ByteArray = ByteArray(64) { 0x22 }, // arbitrary; >= SEAL_OVERHEAD
        signature: ByteArray = ByteArray(MailboxEnvelope.SIG_LENGTH) { (it and 0x7F).toByte() },
        createdAt: Long = 1_700_000_000L,
    ) = MailboxEnvelope(
        id = id,
        toPub = PublicKey(ByteArray(32) { 0x33 }),
        fromPub = PublicKey(ByteArray(32) { 0x44 }),
        createdAt = createdAt,
        ciphertext = ciphertext,
        signature = signature,
    )

    @Test
    fun envelope_wireBytes_roundTrip_preservesEveryField() {
        val env = sampleEnvelope()
        val decoded = MailboxEnvelope.fromWire(env.wireBytes())
        assertContentEquals(env.id, decoded.id)
        assertContentEquals(env.toPub.bytes, decoded.toPub.bytes)
        assertContentEquals(env.fromPub.bytes, decoded.fromPub.bytes)
        assertEquals(env.createdAt, decoded.createdAt)
        assertContentEquals(env.ciphertext, decoded.ciphertext)
        assertContentEquals(env.signature, decoded.signature)
    }

    @Test
    fun envelope_signedBytes_isDeterministic_acrossInstances() {
        val a = sampleEnvelope()
        val b = sampleEnvelope()
        assertContentEquals(a.signedBytes(), b.signedBytes())
    }

    @Test
    fun envelope_signedBytes_changesIfCiphertextChanges() {
        val a = sampleEnvelope(ciphertext = ByteArray(64) { 0x22 }).signedBytes()
        val b = sampleEnvelope(ciphertext = ByteArray(64) { 0x33 }).signedBytes()
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun envelope_fromWire_rejectsTruncatedInput() {
        val full = sampleEnvelope().wireBytes()
        val truncated = full.copyOf(full.size - 5)
        assertFailsWith<RuntimeException> { MailboxEnvelope.fromWire(truncated) }
    }

    @Test
    fun envelope_rejectsCiphertextShorterThanSealOverhead() {
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope(ciphertext = ByteArray(MailboxEnvelope.SEAL_OVERHEAD - 1))
        }
    }

    @Test
    fun envelope_rejectsBadIdLength() {
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope(id = ByteArray(MailboxEnvelope.ID_LENGTH - 1))
        }
    }

    @Test
    fun envelope_rejectsBadSignatureLength() {
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope(signature = ByteArray(MailboxEnvelope.SIG_LENGTH - 1))
        }
    }

    // ---- MailboxBinding ----

    private fun sampleBinding(
        onion: String? = "pmko53q5zuiiocap4wbwb7wne6k34forefrl7ngfls3aipfeoxfbfvqd",
        createdAt: Long = 1_700_000_000L,
        validitySeconds: Long = MailboxBinding.DEFAULT_VALIDITY_SECONDS,
        signature: ByteArray = ByteArray(MailboxBinding.SIG_LENGTH) { (it and 0x7F).toByte() },
    ) = MailboxBinding(
        version = MailboxBinding.VERSION,
        ownerPub = PublicKey(ByteArray(32) { 0x55 }),
        mailboxPub = PublicKey(ByteArray(32) { 0x66 }),
        mailboxOnion = onion,
        createdAt = createdAt,
        expiresAt = createdAt + validitySeconds,
        signature = signature,
    )

    @Test
    fun binding_wireBytes_roundTrip_preservesEveryField_withOnion() {
        val b = sampleBinding()
        val decoded = MailboxBinding.fromWire(b.wireBytes())
        assertEquals(b.version, decoded.version)
        assertContentEquals(b.ownerPub.bytes, decoded.ownerPub.bytes)
        assertContentEquals(b.mailboxPub.bytes, decoded.mailboxPub.bytes)
        assertEquals(b.mailboxOnion, decoded.mailboxOnion)
        assertEquals(b.createdAt, decoded.createdAt)
        assertEquals(b.expiresAt, decoded.expiresAt)
        assertContentEquals(b.signature, decoded.signature)
    }

    @Test
    fun binding_wireBytes_roundTrip_preservesNullOnion() {
        val b = sampleBinding(onion = null)
        val decoded = MailboxBinding.fromWire(b.wireBytes())
        assertEquals(null, decoded.mailboxOnion)
    }

    @Test
    fun binding_signedBytes_isDeterministic_acrossInstances() {
        val a = sampleBinding()
        val b = sampleBinding()
        assertContentEquals(a.signedBytes(), b.signedBytes())
    }

    @Test
    fun binding_signedBytes_doesNotIncludeSignature() {
        // Two bindings differing only by the signature must produce
        // identical signedBytes — otherwise the signature would have to
        // sign itself, which is impossible. Sanity-check the codec
        // separates signed and signature fields.
        val a = sampleBinding(signature = ByteArray(MailboxBinding.SIG_LENGTH) { 0x01 })
        val b = sampleBinding(signature = ByteArray(MailboxBinding.SIG_LENGTH) { 0x02 })
        assertContentEquals(a.signedBytes(), b.signedBytes())
    }

    @Test
    fun binding_rejectsBadOnionLength() {
        assertFailsWith<IllegalArgumentException> {
            sampleBinding(onion = "tooshort.onion")
        }
    }

    @Test
    fun binding_rejectsExpiryBeforeCreation() {
        assertFailsWith<IllegalArgumentException> {
            MailboxBinding(
                version = MailboxBinding.VERSION,
                ownerPub = PublicKey(ByteArray(32) { 0x55 }),
                mailboxPub = PublicKey(ByteArray(32) { 0x66 }),
                mailboxOnion = null,
                createdAt = 1000L,
                expiresAt = 500L, // before created
                signature = ByteArray(MailboxBinding.SIG_LENGTH),
            )
        }
    }
}
