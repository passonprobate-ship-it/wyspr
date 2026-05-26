package com.wyspr.core.transport.wifidirect

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

class WifiDirectLink(
    override val endpoint: PeerEndpoint,
    private val socket: Socket,
) : Link {

    private val sendLock = Mutex()
    private val closeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incoming = Channel<ByteArray>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    @Volatile private var closed = false

    init {
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
                out.write((len ushr 24) and 0xFF)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
                out.write(frame)
                out.flush()
            }
        }
    }

    override fun incoming(): Flow<ByteArray> = _incoming.receiveAsFlow()

    override suspend fun close(): Unit = closeLock.withLock {
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
            Log.w(TAG, "read: cannot open input stream", t)
            return
        }
        try {
            while (!closed) {
                val len = try {
                    stream.readInt()
                } catch (_: EOFException) {
                    return
                } catch (t: Throwable) {
                    if (!closed) Log.w(TAG, "read: header error", t)
                    return
                }
                if (len < 0 || len > MAX_FRAME_BYTES) {
                    Log.w(TAG, "read: peer sent invalid frame length $len")
                    return
                }
                val payload = ByteArray(len)
                try {
                    stream.readFully(payload)
                } catch (_: EOFException) {
                    return
                } catch (t: Throwable) {
                    if (!closed) Log.w(TAG, "read: payload error", t)
                    return
                }
                _incoming.send(payload)
            }
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            // close() ran while we were mid-send; benign.
        } finally {
            closed = true
            runCatching { _incoming.close() }
            runCatching { socket.close() }
        }
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 262_144
        private const val TAG = "WifiDirectLink"
    }
}
