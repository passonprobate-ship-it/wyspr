package com.wyspr.core.trust

import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round-trip tests for the InvitationCertificate canonical CBOR codec.
 * No native libraries here — we exercise [signedBytes], [wireBytes], and
 * [fromWire] with a precomputed-byte signature so the tests run on the
 * JVM without lazysodium.
 */
class InvitationCertificateCodecTest {

    private fun sample(
        version: Int = InvitationCertificate.VERSION,
        signature: ByteArray = ByteArray(InvitationCertificate.SIG_LENGTH) { (it and 0x7F).toByte() },
        vouch: InvitationCertificate.VouchLevel = InvitationCertificate.VouchLevel.PROVISIONAL,
    ) = InvitationCertificate(
        version = version,
        inviterPub = PublicKey(ByteArray(32) { 0x11.toByte() }),
        inviteePub = PublicKey(ByteArray(32) { 0x22.toByte() }),
        communityId = CommunityId(ByteArray(32) { 0x33.toByte() }),
        issuedAt = 100_000L,
        expiresAt = 100_000L + 24L * 60 * 60,
        vouchLevel = vouch,
        nonce = ByteArray(InvitationCertificate.NONCE_LENGTH) { 0x44.toByte() },
        signature = signature,
    )

    @Test
    fun wireBytes_roundTrip_preservesEveryField() {
        val cert = sample()
        val decoded = InvitationCertificate.fromWire(cert.wireBytes())
        assertEquals(cert.version, decoded.version)
        assertContentEquals(cert.inviterPub.bytes, decoded.inviterPub.bytes)
        assertContentEquals(cert.inviteePub.bytes, decoded.inviteePub.bytes)
        assertContentEquals(cert.communityId.bytes, decoded.communityId.bytes)
        assertEquals(cert.issuedAt, decoded.issuedAt)
        assertEquals(cert.expiresAt, decoded.expiresAt)
        assertEquals(cert.vouchLevel, decoded.vouchLevel)
        assertContentEquals(cert.nonce, decoded.nonce)
        assertContentEquals(cert.signature, decoded.signature)
    }

    @Test
    fun signedBytes_isDeterministic_acrossInstances() {
        val a = sample()
        val b = sample()
        assertContentEquals(a.signedBytes(), b.signedBytes())
    }

    @Test
    fun signedBytes_changesIfVouchLevelChanges() {
        val provisional = sample(vouch = InvitationCertificate.VouchLevel.PROVISIONAL).signedBytes()
        val full = sample(vouch = InvitationCertificate.VouchLevel.FULL).signedBytes()
        assert(!provisional.contentEquals(full)) {
            "PROVISIONAL and FULL must produce different signed bytes"
        }
    }

    @Test
    fun fromWire_rejectsTruncatedInput() {
        val full = sample().wireBytes()
        val truncated = full.copyOf(full.size - 5)
        // Cbor.Reader.bytes() uses require() (IAE) when the slice overruns;
        // requireEnd() uses error() (ISE). Either is acceptable here — we
        // just need the decoder to refuse the truncated input.
        assertFailsWith<RuntimeException> {
            InvitationCertificate.fromWire(truncated)
        }
    }

    @Test
    fun vouchLevel_tag_isStable() {
        // If these tags ever change, every persisted cert breaks. Pin them.
        assertEquals(1, InvitationCertificate.VouchLevel.PROVISIONAL.tag)
        assertEquals(2, InvitationCertificate.VouchLevel.FULL.tag)
    }

    @Test
    fun revocation_roundTrip_preservesEveryField() {
        val rev = RevocationCertificate(
            version = RevocationCertificate.VERSION,
            issuerPub = PublicKey(ByteArray(32) { 0xAA.toByte() }),
            targetPub = PublicKey(ByteArray(32) { 0xBB.toByte() }),
            communityId = CommunityId(ByteArray(32) { 0xCC.toByte() }),
            issuedAt = 200_000L,
            reasonCode = RevocationCertificate.ReasonCode.INFILTRATOR,
            signature = ByteArray(RevocationCertificate.SIG_LENGTH) { (it and 0x3F).toByte() },
        )
        val decoded = RevocationCertificate.fromWire(rev.wireBytes())
        // Data class equality compares ByteArrays by reference, so we
        // compare field-by-field.
        assertEquals(rev.version, decoded.version)
        assertContentEquals(rev.issuerPub.bytes, decoded.issuerPub.bytes)
        assertContentEquals(rev.targetPub.bytes, decoded.targetPub.bytes)
        assertContentEquals(rev.communityId.bytes, decoded.communityId.bytes)
        assertEquals(rev.issuedAt, decoded.issuedAt)
        assertEquals(rev.reasonCode, decoded.reasonCode)
        assertContentEquals(rev.signature, decoded.signature)
    }
}
