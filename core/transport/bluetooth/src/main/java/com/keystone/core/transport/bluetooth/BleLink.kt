package com.keystone.core.transport.bluetooth

import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One half of a BLE GATT-backed [Link]. The class is direction-agnostic
 * — both the GATT-client and GATT-server sides instantiate one of these
 * and drive it via [enqueueOutbound] (to be picked up by the appropriate
 * BLE write callback) and [ingestInbound] (called when bytes arrive from
 * the remote).
 *
 * Framing — PROTOCOLS.md §1:
 *
 *     [ frame_len: u16 (big-endian) ][ payload: bytes ]
 *
 * Each [send] writes a single length-prefixed frame; the chunker splits
 * it into MTU-sized fragments at the byte-pump layer (see
 * [BleLinkAssembler]). The [incoming] flow emits one ByteArray per frame
 * — never partials.
 */
internal class BleLink(
    override val endpoint: PeerEndpoint,
    private val outboundSink: SendChannel<ByteArray>,
    private val onClose: suspend () -> Unit,
) : Link {

    private val assembler = BleLinkAssembler()

    /**
     * A buffered Channel — not a SharedFlow — because the handshake
     * subscribes to [incoming] *after* the Link has been handed out,
     * and frames may already have arrived. A SharedFlow with replay=0
     * would silently drop those, breaking the Noise XX exchange. The
     * Channel preserves all frames until consumption.
     *
     * Capacity 64 is enough to absorb a full handshake + a few sync
     * frames; beyond that we SUSPEND the BLE callback thread, which
     * applies natural backpressure on the radio.
     */
    private val inbound = Channel<ByteArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val closeLock = Mutex()
    @Volatile private var closed = false

    /**
     * Take raw bytes received from the remote (one MTU-chunk at a time
     * for BLE) and enqueue completed frames on [incoming].
     */
    suspend fun ingestInbound(chunk: ByteArray) {
        for (frame in assembler.feed(chunk)) {
            inbound.send(frame)
        }
    }

    override suspend fun send(frame: ByteArray) {
        require(!closed) { "link is closed" }
        require(frame.size <= MAX_FRAME_BYTES) {
            "frame ${frame.size} > MAX_FRAME_BYTES $MAX_FRAME_BYTES"
        }
        val len = frame.size
        val framed = ByteArray(2 + len)
        framed[0] = ((len ushr 8) and 0xFF).toByte()
        framed[1] = (len and 0xFF).toByte()
        System.arraycopy(frame, 0, framed, 2, len)
        outboundSink.send(framed)
    }

    override fun incoming(): Flow<ByteArray> = inbound.consumeAsFlow()

    override suspend fun close(): Unit = closeLock.withLock {
        if (closed) return
        closed = true
        outboundSink.close()
        inbound.close()
        onClose()
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 16_384
    }
}

/**
 * Reassembles incoming BLE chunks (which may split or coalesce frames)
 * back into length-prefixed frames. Single-threaded — the BLE callback
 * thread feeds it sequentially.
 */
internal class BleLinkAssembler {
    private val buffer = ArrayDeque<Byte>()
    private val pending = mutableListOf<ByteArray>()

    fun feed(chunk: ByteArray): List<ByteArray> {
        pending.clear()
        for (b in chunk) buffer.addLast(b)

        while (true) {
            if (buffer.size < 2) break
            val hi = (buffer[0].toInt() and 0xFF)
            val lo = (buffer[1].toInt() and 0xFF)
            val frameLen = (hi shl 8) or lo
            require(frameLen <= BleLink.MAX_FRAME_BYTES) {
                "incoming frame length $frameLen exceeds MAX_FRAME_BYTES"
            }
            if (buffer.size < 2 + frameLen) break
            // pull off header + frame
            buffer.removeFirst(); buffer.removeFirst()
            val frame = ByteArray(frameLen)
            for (i in 0 until frameLen) frame[i] = buffer.removeFirst()
            pending.add(frame)
        }
        return pending.toList()
    }
}

/**
 * Outbound chunker — accepts framed bytes from a [BleLink] and splits
 * them into MTU-sized chunks to feed the BLE write/notify pipe.
 */
internal class BleOutboundChunker(private val mtuPayload: Int) {
    init { require(mtuPayload in 20..512) { "mtu payload out of typical range" } }

    fun chunk(framed: ByteArray): List<ByteArray> {
        if (framed.size <= mtuPayload) return listOf(framed)
        val out = ArrayList<ByteArray>((framed.size + mtuPayload - 1) / mtuPayload)
        var i = 0
        while (i < framed.size) {
            val end = (i + mtuPayload).coerceAtMost(framed.size)
            out.add(framed.copyOfRange(i, end))
            i = end
        }
        return out
    }
}

/**
 * Convenience factory used by tests and by the production GATT wiring.
 * The production wiring supplies a Channel whose receiver loops feed
 * the BLE write/notify API; tests can supply a fake channel.
 */
internal fun newBleLink(
    endpoint: PeerEndpoint,
    onClose: suspend () -> Unit,
): Pair<BleLink, Channel<ByteArray>> {
    val sink = Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.SUSPEND)
    val link = BleLink(endpoint = endpoint, outboundSink = sink, onClose = onClose)
    return link to sink
}
