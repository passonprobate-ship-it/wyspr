package com.keystone.core.transport.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reassembly of length-prefixed BLE frames split arbitrarily across
 * MTU-sized chunks. The assembler runs on the BLE callback thread, so
 * these tests are single-threaded too.
 */
class BleLinkAssemblerTest {

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = ((payload.size ushr 8) and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    @Test
    fun feed_completeFrame_inOneChunk() {
        val a = BleLinkAssembler()
        val payload = ByteArray(10) { it.toByte() }
        val frames = a.feed(frame(payload))
        assertEquals(1, frames.size)
        assertContentEquals(payload, frames[0])
    }

    @Test
    fun feed_splitsHeaderAndPayloadAcrossChunks() {
        val a = BleLinkAssembler()
        val payload = ByteArray(50) { it.toByte() }
        val framed = frame(payload)
        // Header alone — no full frame yet.
        assertEquals(0, a.feed(framed.copyOfRange(0, 2)).size)
        // Partial payload — still no full frame.
        assertEquals(0, a.feed(framed.copyOfRange(2, 25)).size)
        // Rest of payload — yields one frame.
        val out = a.feed(framed.copyOfRange(25, framed.size))
        assertEquals(1, out.size)
        assertContentEquals(payload, out[0])
    }

    @Test
    fun feed_coalescesMultipleFrames() {
        val a = BleLinkAssembler()
        val one = ByteArray(5) { 1 }
        val two = ByteArray(7) { 2 }
        val combined = frame(one) + frame(two)
        val frames = a.feed(combined)
        assertEquals(2, frames.size)
        assertContentEquals(one, frames[0])
        assertContentEquals(two, frames[1])
    }

    @Test
    fun feed_rejectsFrameLargerThanMaxFrameBytes() {
        val a = BleLinkAssembler()
        val hi = (((BleLink.MAX_FRAME_BYTES + 1) ushr 8) and 0xFF).toByte()
        val lo = ((BleLink.MAX_FRAME_BYTES + 1) and 0xFF).toByte()
        assertFailsWith<IllegalArgumentException> {
            a.feed(byteArrayOf(hi, lo))
        }
    }

    @Test
    fun chunker_singleChunkBelowMtu() {
        val c = BleOutboundChunker(mtuPayload = 100)
        val out = c.chunk(ByteArray(30))
        assertEquals(1, out.size)
        assertEquals(30, out[0].size)
    }

    @Test
    fun chunker_splitsAtMtuPayloadBoundary() {
        val c = BleOutboundChunker(mtuPayload = 20)
        val out = c.chunk(ByteArray(50) { it.toByte() })
        assertEquals(3, out.size)
        assertEquals(20, out[0].size)
        assertEquals(20, out[1].size)
        assertEquals(10, out[2].size)
    }
}
