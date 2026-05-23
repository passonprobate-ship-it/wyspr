package com.wyspr.core.transport.bluetooth

import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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
 *
 * ## Threading
 *
 * [ingestInbound] is synchronous (non-suspending) and safe to call from
 * the Android BLE binder thread. It enqueues the raw chunk onto a
 * single-consumer Channel; a dedicated drainer coroutine (started via
 * [startInboundDrainer]) feeds the chunks through the assembler in
 * arrival order. This guarantees ordered, race-free reassembly even
 * when BLE delivers two notifications back-to-back. Earlier versions
 * launched one `ioScope.launch { ingestInbound(value) }` per chunk,
 * which on a multi-threaded dispatcher allowed feed() to run
 * concurrently and corrupt the assembler's deque.
 *
 * ## Incoming flow
 *
 * [incoming] returns `inbound.receiveAsFlow()` — multiple sequential
 * collectors are allowed. The Noise XX handshake re-collects per frame
 * (m1/m2/m3 + cert exchange), so a one-shot `consumeAsFlow()` would
 * throw on the second collect. Pre-subscription emissions are
 * preserved by the 64-slot Channel buffer.
 */
internal class BleLink(
    override val endpoint: PeerEndpoint,
    private val outboundSink: SendChannel<ByteArray>,
    private val onClose: suspend () -> Unit,
) : Link {

    private val assembler = BleLinkAssembler()

    /**
     * Raw BLE chunks as they arrive from the binder thread. Capacity is
     * generous because the Noise XX exchange can briefly queue many
     * MTU-sized chunks before the drainer wakes; `DROP_OLDEST` is the
     * wrong default for a stream-oriented protocol, so the buffer is
     * sized to never reach saturation in practice (typical handshake:
     * ~30 chunks total).
     */
    private val rawChunks = Channel<ByteArray>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Reassembled length-prefixed frames. See class-level KDoc for the
     * receiveAsFlow rationale.
     */
    private val inbound = Channel<ByteArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val closeLock = Mutex()
    @Volatile private var closed = false
    @Volatile private var drainerJob: Job? = null

    /**
     * Enqueue a raw BLE chunk for reassembly. Safe to call from any
     * thread including the Android BLE binder callback thread; never
     * suspends, never throws — a closed link silently drops chunks.
     */
    fun ingestInbound(chunk: ByteArray) {
        val result: ChannelResult<Unit> = rawChunks.trySend(chunk)
        // trySend can only fail if the channel is full or closed; full
        // would mean the drainer is wedged (handshake-layer bug) or
        // the buffer is undersized. Closed means the link is closed.
        // In neither case is there a useful recovery in the BLE
        // callback context; we just drop.
        result.getOrNull()
    }

    /**
     * Start the single drainer coroutine that reads from [rawChunks]
     * and feeds the assembler in arrival order. MUST be called once
     * before any consumer collects [incoming]. Returns the Job so the
     * caller can cancel it on transport-level teardown.
     */
    fun startInboundDrainer(scope: CoroutineScope): Job {
        check(drainerJob == null) { "drainer already started" }
        val job = scope.launch {
            try {
                for (chunk in rawChunks) {
                    val frames = try {
                        assembler.feed(chunk)
                    } catch (e: IllegalArgumentException) {
                        // Bad frame length from peer (oversized header).
                        // Close inbound with the cause so the handshake
                        // surfaces TransportFailed instead of hanging
                        // on FRAME_TIMEOUT_MS.
                        inbound.close(e)
                        break
                    }
                    for (frame in frames) inbound.send(frame)
                }
            } finally {
                // Drainer exiting (channel closed or cancelled) — close
                // the downstream channel so any collector terminates.
                inbound.close()
            }
        }
        drainerJob = job
        return job
    }

    override suspend fun send(frame: ByteArray) {
        require(!closed) { "link is closed" }
        require(frame.size <= MAX_FRAME_BYTES) {
            "frame ${frame.size} > MAX_FRAME_BYTES $MAX_FRAME_BYTES"
        }
        val len = frame.size
        val framed = ByteArray(4 + len)
        framed[0] = ((len ushr 24) and 0xFF).toByte()
        framed[1] = ((len ushr 16) and 0xFF).toByte()
        framed[2] = ((len ushr 8) and 0xFF).toByte()
        framed[3] = (len and 0xFF).toByte()
        System.arraycopy(frame, 0, framed, 4, len)
        outboundSink.send(framed)
    }

    override fun incoming(): Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun close(): Unit = closeLock.withLock {
        if (closed) return
        closed = true
        outboundSink.close()
        rawChunks.close()
        // The drainer will observe rawChunks.isClosedForReceive and
        // close `inbound` in its finally block. If the drainer was
        // never started (early teardown), close inbound directly.
        if (drainerJob == null) inbound.close()
        onClose()
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 262_144
    }
}

/**
 * Reassembles incoming BLE chunks (which may split or coalesce frames)
 * back into length-prefixed frames. Single-threaded — called only from
 * [BleLink]'s drainer coroutine.
 */
internal class BleLinkAssembler {
    // Primitive byte buffer — boxed `Byte` in an ArrayDeque allocated
    // box objects per incoming byte, which dominated reassembly cost
    // on the 13KB Push frames the photo path generates.
    //
    // Layout: [readPos, writePos) holds unread bytes. We compact when
    // readPos > 0 and the tail is at capacity. Sized to one peer's
    // worst-case in-flight (one 16KB frame + chunk overhead).
    private var buf = ByteArray(MAX_FRAME_BYTES + 256)
    private var readPos = 0
    private var writePos = 0
    private val pending = mutableListOf<ByteArray>()

    private fun ensureRoom(addBytes: Int) {
        val needed = writePos + addBytes
        if (needed <= buf.size) return
        // Try compaction first.
        if (readPos > 0) {
            val live = writePos - readPos
            System.arraycopy(buf, readPos, buf, 0, live)
            writePos = live
            readPos = 0
            if (writePos + addBytes <= buf.size) return
        }
        // Grow up to MAX_FRAME_BYTES * 2 — anything past that is a
        // peer misbehaving, the frame-length require() below will
        // catch it.
        val newSize = (buf.size * 2).coerceAtMost(MAX_FRAME_BYTES * 2 + 256)
        require(newSize >= writePos + addBytes) {
            "reassembly buffer exhausted: writing $addBytes into ${buf.size - writePos}"
        }
        buf = buf.copyOf(newSize)
    }

    fun feed(chunk: ByteArray): List<ByteArray> {
        pending.clear()
        ensureRoom(chunk.size)
        System.arraycopy(chunk, 0, buf, writePos, chunk.size)
        writePos += chunk.size

        while (true) {
            val live = writePos - readPos
            if (live < 4) break
            val b0 = buf[readPos].toInt() and 0xFF
            val b1 = buf[readPos + 1].toInt() and 0xFF
            val b2 = buf[readPos + 2].toInt() and 0xFF
            val b3 = buf[readPos + 3].toInt() and 0xFF
            val frameLen = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            require(frameLen in 0..MAX_FRAME_BYTES) {
                "incoming frame length $frameLen out of range 0..$MAX_FRAME_BYTES"
            }
            if (live < 4 + frameLen) break
            val frame = ByteArray(frameLen)
            System.arraycopy(buf, readPos + 4, frame, 0, frameLen)
            readPos += 4 + frameLen
            pending.add(frame)
        }
        // Once everything is consumed, reset to the start so we don't
        // grow the buffer indefinitely under steady traffic.
        if (readPos == writePos) {
            readPos = 0
            writePos = 0
        }
        return pending.toList()
    }

    private companion object {
        const val MAX_FRAME_BYTES: Int = BleLink.MAX_FRAME_BYTES
    }
}

/**
 * Outbound chunker — accepts framed bytes from a [BleLink] and splits
 * them into MTU-sized chunks to feed the BLE write/notify pipe.
 */
internal class BleOutboundChunker(private val mtuPayload: Int) {
    // Lower bound: ATT MTU min is 23, payload = 23 - 3 = 20.
    // Upper bound: GATT_MAX_ATTR_LEN is 512 in Android — `BluetoothGatt
    // .writeCharacteristic` throws IllegalArgumentException("value
    // should not be longer than max length of an attribute value") at
    // anything above. The ATT MTU itself can negotiate up to 517 on
    // BLE 5.x (Samsung stack does this even when we request 247) so
    // the raw MTU isn't a safe payload bound. Callers must clamp
    // their `mtuPayload` to 512.
    init { require(mtuPayload in 20..512) { "mtu payload $mtuPayload out of [20, 512]" } }

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
