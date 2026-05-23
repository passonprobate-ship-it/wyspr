package com.wyspr.app.transport

import android.util.Log
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/**
 * TCP-backed [Link] for traffic routed over Tor.
 *
 * Framing matches the contract in [Link]: each call to [send] writes
 * one length-prefixed frame to the wire, and [incoming] emits one
 * [ByteArray] per frame that arrives.
 *
 *     [ frame_len: u16 big-endian ][ payload: bytes ]   (max 16384)
 *
 * The u16 length matches BleLink so a Noise session can be carried
 * over either transport without re-framing. Anything above 65535
 * would be rejected by the wire format anyway; we cap at 16384 (same
 * as BleLink's `MAX_FRAME_BYTES`) so a misbehaving peer can't pin a
 * 64 KiB buffer per frame.
 *
 * Lifecycle: the link owns the [socket] and an internal read coroutine.
 * [close] is idempotent and disposes both. The socket-read coroutine
 * fans frames into [incoming] via a [MutableSharedFlow] with a small
 * extra buffer; a slow consumer suspends the reader rather than
 * dropping frames, which matches BleLink's contract.
 */
class TorLink(
    override val endpoint: PeerEndpoint,
    private val socket: Socket,
) : Link {

    private val sendLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Channel — not SharedFlow — for single-consumer at-most-once
    // delivery semantics that match BleLink. A SharedFlow with replay=0
    // would let a later collector subscribe partway through the Noise
    // session and see zero frames; the reader is already past them.
    // Channel guarantees each frame goes to exactly one collector and
    // none are dropped silently.
    private val _incoming = Channel<ByteArray>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    @Volatile private var closed = false

    init {
        // Tor circuits are inherently latent (~250–800ms RTT); the
        // default 3-byte TCP_NODELAY off is fine for a handshake
        // since Noise messages are larger than the MSS anyway, but
        // turning it on shaves a Nagle delay off small acks at the
        // end of the cert exchange.
        runCatching { socket.tcpNoDelay = true }
        scope.launch { runReadLoop() }
    }

    override suspend fun send(frame: ByteArray) {
        require(frame.size <= MAX_FRAME_BYTES) {
            "frame ${frame.size} > MAX_FRAME_BYTES $MAX_FRAME_BYTES"
        }
        if (closed) throw IOException("link closed")
        sendLock.withLock {
            withContext(Dispatchers.IO) {
                val out = socket.getOutputStream()
                val len = frame.size
                // Big-endian u16 length header.
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
                out.write(frame)
                out.flush()
            }
        }
    }

    override fun incoming(): Flow<ByteArray> = _incoming.receiveAsFlow()

    override suspend fun close() {
        if (closed) return
        closed = true
        _incoming.close()
        withContext(Dispatchers.IO) {
            runCatching { socket.shutdownInput() }
            runCatching { socket.shutdownOutput() }
            runCatching { socket.close() }
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun runReadLoop() {
        val stream = try {
            DataInputStream(socket.getInputStream())
        } catch (t: Throwable) {
            Log.w(TAG, "TorLink read: cannot open input stream", t)
            return
        }
        try {
            while (!closed) {
                val len = try {
                    stream.readUnsignedShort()
                } catch (_: EOFException) {
                    return
                } catch (t: Throwable) {
                    if (!closed) Log.w(TAG, "TorLink read: header error", t)
                    return
                }
                if (len > MAX_FRAME_BYTES) {
                    Log.w(TAG, "TorLink read: peer sent oversize frame $len")
                    return
                }
                val payload = ByteArray(len)
                try {
                    stream.readFully(payload)
                } catch (_: EOFException) {
                    return
                } catch (t: Throwable) {
                    if (!closed) Log.w(TAG, "TorLink read: payload error", t)
                    return
                }
                _incoming.send(payload)
            }
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            // close() ran while we were mid-send; benign.
        } finally {
            // Best-effort close — the suspending close() above might
            // have raced us to it; either way the socket ends up shut.
            runCatching { _incoming.close() }
            runCatching { socket.close() }
        }
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 16_384
        private const val TAG = "TorLink"
    }
}
