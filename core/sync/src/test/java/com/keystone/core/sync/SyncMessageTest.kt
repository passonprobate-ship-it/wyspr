package com.keystone.core.sync

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncMessageTest {

    private val community = ByteArray(32) { 0xAA.toByte() }

    @Test fun have_set_round_trip() {
        val msg = SyncMessage.HaveSet(
            community = community,
            keys = listOf(
                EnvelopeKey(0x12, ByteArray(40) { 0x01 }),
                EnvelopeKey(0x10, ByteArray(32) { 0x02 }),
            ),
        )
        val bytes = SyncMessage.encode(msg)
        val decoded = SyncMessage.decode(bytes) as SyncMessage.HaveSet
        assertContentEquals(msg.community, decoded.community)
        assertEquals(msg.keys, decoded.keys)
    }

    @Test fun want_round_trip() {
        val msg = SyncMessage.Want(
            community = community,
            keys = listOf(EnvelopeKey(0x14, ByteArray(32) { 0x05 })),
        )
        val decoded = SyncMessage.decode(SyncMessage.encode(msg)) as SyncMessage.Want
        assertEquals(msg.keys, decoded.keys)
    }

    @Test fun push_round_trip() {
        val msg = SyncMessage.Push(
            rows = listOf(
                EnvelopeRow(
                    community = community,
                    typeTag = 0x12,
                    primaryKey = ByteArray(40) { 0x11 },
                    body = ByteArray(80) { 0x22 },
                    observedAt = 1_700_000_000L,
                ),
            ),
        )
        val decoded = SyncMessage.decode(SyncMessage.encode(msg)) as SyncMessage.Push
        assertEquals(1, decoded.rows.size)
        assertEquals(msg.rows[0], decoded.rows[0])
    }

    @Test fun encode_is_deterministic() {
        val msg = SyncMessage.HaveSet(community, listOf(EnvelopeKey(0x10, ByteArray(32))))
        assertContentEquals(SyncMessage.encode(msg), SyncMessage.encode(msg))
    }

    @Test fun decode_rejects_unknown_tag() {
        // Build a CBOR array [0x99, ...] — unknown tag.
        val garbage = byteArrayOf(
            0x82.toByte(), // array of 2
            0x18.toByte(), 0x99.toByte(), // uint(0x99) — unknown tag
            0x40.toByte(), // empty bstr
        )
        assertFailsWith<IllegalStateException> { SyncMessage.decode(garbage) }
    }

    @Test fun empty_have_set_round_trips() {
        val msg = SyncMessage.HaveSet(community, emptyList())
        val decoded = SyncMessage.decode(SyncMessage.encode(msg)) as SyncMessage.HaveSet
        assertTrue(decoded.keys.isEmpty())
    }
}
