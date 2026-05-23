package com.wyspr.core.currency

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TransferTest {

    private fun sample(
        memoHash: ByteArray? = null,
        amount: Long = 250L,
        seq: Long = 7L,
    ) = Transfer(
        version = Transfer.VERSION,
        community = ByteArray(32) { 0xAA.toByte() },
        sender = ByteArray(32) { 0x11 },
        recipient = ByteArray(32) { 0x22 },
        amount = amount,
        seq = seq,
        memoHash = memoHash,
        issuedAt = 1_700_000_000L,
        nonce = ByteArray(16) { 0x33 },
        signature = ByteArray(64) { 0x44 },
    )

    @Test fun round_trip_with_null_memo() {
        val t = sample()
        val decoded = Transfer.decode(t.encode())
        assertEquals(t, decoded)
    }

    @Test fun round_trip_with_memo() {
        val t = sample(memoHash = ByteArray(32) { 0x55 })
        val decoded = Transfer.decode(t.encode())
        assertEquals(t, decoded)
    }

    @Test fun encode_is_deterministic() {
        assertContentEquals(sample().encode(), sample().encode())
    }

    @Test fun signed_bytes_excludes_signature() {
        val a = sample()
        val b = a.copy(signature = ByteArray(64) { 0x77 })
        assertContentEquals(a.signedBytes(), b.signedBytes())
    }

    @Test fun signed_bytes_matches_helper() {
        val t = sample(memoHash = ByteArray(32) { 0x66 })
        val helper = Transfer.signedBytesOf(
            community = t.community,
            sender = t.sender,
            recipient = t.recipient,
            amount = t.amount,
            seq = t.seq,
            memoHash = t.memoHash,
            issuedAt = t.issuedAt,
            nonce = t.nonce,
        )
        assertContentEquals(t.signedBytes(), helper)
    }

    /**
     * Double-spend produces distinct signedBytes. Two transfers from the
     * same sender with the same seq but different recipients (or amounts,
     * or memos) yield different signedBytes — and therefore distinct
     * signatures the sender can't disavow. This is the cryptographic
     * basis for §6 slash detection.
     */
    @Test fun double_spend_yields_distinct_signed_bytes() {
        val a = sample().copy(recipient = ByteArray(32) { 0x22 }, amount = 100)
        val b = sample().copy(recipient = ByteArray(32) { 0x33 }, amount = 100)
        assertNotEquals(a.signedBytes().toList(), b.signedBytes().toList())
        // Same (sender, seq) — would be caught by the slash detector.
        assertEquals(a.sender.toList(), b.sender.toList())
        assertEquals(a.seq, b.seq)
    }

    @Test fun rejects_negative_amount() {
        assertFailsWith<IllegalArgumentException> { sample(amount = -1) }
    }

    @Test fun rejects_negative_seq() {
        assertFailsWith<IllegalArgumentException> { sample(seq = -1) }
    }

    @Test fun rejects_wrong_memo_size() {
        assertFailsWith<IllegalArgumentException> {
            sample(memoHash = ByteArray(31))
        }
    }

    @Test fun decode_rejects_truncated_bytes() {
        val good = sample().encode()
        assertFailsWith<IllegalArgumentException> {
            Transfer.decode(good.copyOf(good.size - 1))
        }
    }

    @Test fun memo_hash_equality_handles_null_asymmetry() {
        val withMemo = sample(memoHash = ByteArray(32) { 0x55 })
        val withoutMemo = sample(memoHash = null)
        // Both directions of the asymmetric comparison must return false.
        assertNotEquals(withMemo, withoutMemo)
        assertNotEquals(withoutMemo, withMemo)
        // Two transfers that both have null memos must still compare equal.
        assertEquals(sample(memoHash = null), sample(memoHash = null))
        // Two transfers with the same non-null memo must compare equal.
        assertEquals(
            sample(memoHash = ByteArray(32) { 0x55 }),
            sample(memoHash = ByteArray(32) { 0x55 }),
        )
    }
}
