package com.keystone.core.transport.bluetooth

import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.Transport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Regression test for the SharedFlow → Channel swap and the
 * single-drainer reassembly model inside [BleLink].
 *
 * The earlier v0.1 implementation:
 *   1. Used `MutableSharedFlow(replay = 0)` which silently dropped
 *      emissions issued before the consumer subscribed.
 *   2. Called `ingestInbound` as a suspending function from per-chunk
 *      `ioScope.launch { ... }` blocks, allowing the assembler's
 *      ArrayDeque to be mutated concurrently from multiple
 *      Dispatchers.IO threads. On real hardware that surfaced as
 *      spurious "frame too large" exceptions.
 *
 * The current model uses a non-suspending [BleLink.ingestInbound] that
 * enqueues raw chunks onto a Channel; a single drainer coroutine
 * (started via [BleLink.startInboundDrainer]) feeds them through the
 * assembler in arrival order. These tests pin both contracts.
 */
class BleLinkChannelTest {

    private val endpoint = PeerEndpoint(Transport.Kind.BluetoothLe, "AA:BB:CC:DD:EE:F0")

    private fun framed(payload: ByteArray): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = ((payload.size ushr 8) and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    @Test
    fun framesArrivingBeforeSubscriber_areNotDropped() = runTest {
        val (link, _) = newBleLink(endpoint) { /* onClose */ }
        link.startInboundDrainer(this)

        val frames = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5, 6, 7),
            byteArrayOf(8, 9),
        )

        // Push BEFORE anyone subscribes — the regression we're guarding
        // against is silent loss of these emissions.
        for (f in frames) link.ingestInbound(framed(f))

        val collected = withTimeout(1_000) {
            link.incoming().take(frames.size).toList()
        }
        assertEquals(frames.size, collected.size)
        for (i in frames.indices) {
            assertContentEquals(frames[i], collected[i], "frame index $i lost or reordered")
        }
        link.close()
    }

    @Test
    fun framesArrivingAfterSubscriber_areAlsoDelivered() = runTest {
        val (link, _) = newBleLink(endpoint) { /* onClose */ }
        link.startInboundDrainer(this)

        val collected = mutableListOf<ByteArray>()
        val collector = launch {
            link.incoming().take(2).collect { collected.add(it) }
        }
        yield()

        link.ingestInbound(framed(byteArrayOf(0x10, 0x11)))
        link.ingestInbound(framed(byteArrayOf(0x20, 0x21, 0x22)))

        collector.join()
        assertEquals(2, collected.size)
        assertContentEquals(byteArrayOf(0x10, 0x11), collected[0])
        assertContentEquals(byteArrayOf(0x20, 0x21, 0x22), collected[1])
        link.close()
    }

    @Test
    fun coalescedChunk_yieldsMultipleFrames() = runTest {
        val (link, _) = newBleLink(endpoint) { /* onClose */ }
        link.startInboundDrainer(this)

        // BLE peripherals sometimes coalesce two notifications into a
        // single inbound chunk. Both frames must survive.
        val combined = framed(byteArrayOf(1, 1, 1)) + framed(byteArrayOf(2, 2))
        link.ingestInbound(combined)

        val received = withTimeout(1_000) {
            link.incoming().take(2).toList()
        }
        assertEquals(2, received.size)
        assertContentEquals(byteArrayOf(1, 1, 1), received[0])
        assertContentEquals(byteArrayOf(2, 2), received[1])
        link.close()
    }

    @Test
    fun outboundSink_receivesFramedBytes() = runTest {
        val (link, sink) = newBleLink(endpoint) { /* onClose */ }
        link.send(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))

        val pushed = sink.receive()
        // [len_hi=0, len_lo=2, 0xAA, 0xBB]
        assertEquals(4, pushed.size)
        assertEquals(0, pushed[0].toInt())
        assertEquals(2, pushed[1].toInt())
        assertEquals(0xAA.toByte(), pushed[2])
        assertEquals(0xBB.toByte(), pushed[3])
    }

    @Test
    fun close_terminatesIncomingFlowCleanly() = runTest {
        val (link, _) = newBleLink(endpoint) { /* onClose */ }
        link.startInboundDrainer(this)

        link.ingestInbound(framed(byteArrayOf(0x01)))
        // Yield so the drainer transfers the chunk into `inbound`
        // before close() runs; otherwise close() races the drainer
        // and the test becomes order-dependent.
        yield()
        link.close()

        val tail = link.incoming().toList()
        assertEquals(1, tail.size)
        assertContentEquals(byteArrayOf(0x01), tail[0])
    }

    @Test
    fun incomingFlowAllowsMultipleSequentialCollectors() = runTest {
        val (link, _) = newBleLink(endpoint) { /* onClose */ }
        link.startInboundDrainer(this)

        // The Noise XX handshake collects incoming() once per frame
        // (m1/m2/m3 + cert exchange). Earlier consumeAsFlow() threw
        // IllegalStateException on the second collect.
        link.ingestInbound(framed(byteArrayOf(0xA0.toByte())))
        link.ingestInbound(framed(byteArrayOf(0xB0.toByte())))
        link.ingestInbound(framed(byteArrayOf(0xC0.toByte())))

        val first = link.incoming().take(1).toList()
        val second = link.incoming().take(1).toList()
        val third = link.incoming().take(1).toList()

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(1, third.size)
        assertContentEquals(byteArrayOf(0xA0.toByte()), first[0])
        assertContentEquals(byteArrayOf(0xB0.toByte()), second[0])
        assertContentEquals(byteArrayOf(0xC0.toByte()), third[0])
        link.close()
    }
}
