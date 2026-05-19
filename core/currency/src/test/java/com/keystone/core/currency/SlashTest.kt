package com.keystone.core.currency

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SlashTest {

    private val community = ByteArray(32) { 0xAA.toByte() }
    private val targetPub = ByteArray(32) { 0x11 }
    private val witnessPub = ByteArray(32) { 0x99.toByte() }

    private fun transfer(
        recipient: ByteArray,
        seq: Long = 7L,
        amount: Long = 100L,
        memoHash: ByteArray? = null,
        signature: ByteArray = ByteArray(64) { 0x33 },
    ) = Transfer(
        version = Transfer.VERSION,
        community = community,
        sender = targetPub,
        recipient = recipient,
        amount = amount,
        seq = seq,
        memoHash = memoHash,
        issuedAt = 1_700_000_000L,
        nonce = ByteArray(16) { 0x44 },
        signature = signature,
    )

    private fun pairOfConflicts(): Pair<Transfer, Transfer> {
        // Same (sender, seq); differ in recipient and signature.
        val a = transfer(recipient = ByteArray(32) { 0x22 }, signature = ByteArray(64) { 0x55 })
        val b = transfer(recipient = ByteArray(32) { 0x33 }, signature = ByteArray(64) { 0x66 })
        return a to b
    }

    private fun sampleSlash(): Slash {
        val (a, b) = pairOfConflicts()
        val (orderedA, orderedB) = Slash.orderEvidence(a, b)
        return Slash(
            version = Slash.VERSION,
            community = community,
            target = targetPub,
            evidenceA = orderedA,
            evidenceB = orderedB,
            issuedAt = 1_700_000_500L,
            witness = witnessPub,
            signature = ByteArray(64) { 0x77 },
        )
    }

    @Test fun round_trip() {
        val s = sampleSlash()
        val decoded = Slash.decode(s.encode())
        assertEquals(s, decoded)
    }

    @Test fun encode_is_deterministic() {
        val a = sampleSlash().encode()
        val b = sampleSlash().encode()
        assertContentEquals(a, b)
    }

    @Test fun signed_bytes_excludes_signature() {
        val s = sampleSlash()
        val alt = s.copy(signature = ByteArray(64) { 0xCC.toByte() })
        assertContentEquals(s.signedBytes(), alt.signedBytes())
    }

    @Test fun signed_bytes_changes_with_evidence() {
        val s = sampleSlash()
        // Different recipient on evidenceA → different signedBytes.
        val (a2, _) = pairOfConflicts()
        val different = transfer(recipient = ByteArray(32) { 0x44 }, signature = ByteArray(64) { 0x88.toByte() })
        val (ordA, ordB) = Slash.orderEvidence(different, a2)
        val s2 = s.copy(evidenceA = ordA, evidenceB = ordB)
        assertNotEquals(s.signedBytes().toList(), s2.signedBytes().toList())
    }

    @Test fun orderEvidence_is_canonical_and_symmetric() {
        val (a, b) = pairOfConflicts()
        val (x1, y1) = Slash.orderEvidence(a, b)
        val (x2, y2) = Slash.orderEvidence(b, a)
        assertEquals(x1, x2)
        assertEquals(y1, y2)
    }

    @Test fun constructor_rejects_non_canonical_evidence_order() {
        // Force the wrong order by swapping the canonical pair.
        val (a, b) = pairOfConflicts()
        val (canonA, canonB) = Slash.orderEvidence(a, b)
        // The reversed pair must not be acceptable.
        assertFailsWith<IllegalArgumentException> {
            Slash(
                version = Slash.VERSION,
                community = community,
                target = targetPub,
                evidenceA = canonB,
                evidenceB = canonA,
                issuedAt = 0L,
                witness = witnessPub,
                signature = ByteArray(64),
            )
        }
    }

    @Test fun rejects_wrong_field_sizes() {
        val (a, b) = pairOfConflicts()
        val (oa, ob) = Slash.orderEvidence(a, b)
        assertFailsWith<IllegalArgumentException> {
            Slash(
                version = 1, community = ByteArray(31), target = targetPub,
                evidenceA = oa, evidenceB = ob, issuedAt = 0,
                witness = witnessPub, signature = ByteArray(64),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Slash(
                version = 1, community = community, target = ByteArray(33),
                evidenceA = oa, evidenceB = ob, issuedAt = 0,
                witness = witnessPub, signature = ByteArray(64),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Slash(
                version = 1, community = community, target = targetPub,
                evidenceA = oa, evidenceB = ob, issuedAt = 0,
                witness = ByteArray(31), signature = ByteArray(64),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Slash(
                version = 1, community = community, target = targetPub,
                evidenceA = oa, evidenceB = ob, issuedAt = 0,
                witness = witnessPub, signature = ByteArray(63),
            )
        }
    }

    @Test fun decode_rejects_truncated() {
        val good = sampleSlash().encode()
        assertFailsWith<IllegalArgumentException> {
            Slash.decode(good.copyOf(good.size - 1))
        }
    }

    @Test fun signedBytesOf_matches_envelope_signedBytes() {
        val s = sampleSlash()
        val direct = Slash.signedBytesOf(
            community = s.community,
            target = s.target,
            evidenceA = s.evidenceA,
            evidenceB = s.evidenceB,
            issuedAt = s.issuedAt,
            witness = s.witness,
        )
        assertContentEquals(s.signedBytes(), direct)
    }
}
