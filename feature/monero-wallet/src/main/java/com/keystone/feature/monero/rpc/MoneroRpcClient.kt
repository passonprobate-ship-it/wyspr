package com.keystone.feature.monero.rpc

import com.keystone.core.transport.Socks5
import com.keystone.feature.monero.MoneroNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks Monero daemon-rpc (JSON-RPC 2.0) over a TCP socket that is
 * itself wrapped through the local Tor SOCKS5 proxy. The Tor exit
 * relay (or, for `.onion` nodes, the responder side of a Tor
 * circuit) is what the remote daemon sees — never the user's IP.
 *
 * This is the v0.7.0 scaffold slice. Only the read-only daemon
 * methods that don't need local crypto are wired:
 *  - [getInfo] — chain tip, peer count, sync flag.
 *
 * The view-key and spend-key methods land in v0.7.0b once the
 * Monero JNI crypto engine is added (see
 * [com.keystone.feature.monero.MoneroCryptoEngine] kdoc).
 *
 * **Why we don't use OkHttp.** OkHttp's `Proxy.SOCKS` constructor
 * does a hostname lookup on the JVM side before connecting — that
 * would leak the Monero node's hostname (or `.onion`) to the local
 * DNS resolver. We need SOCKS5 DOMAINNAME (ATYP=0x03) so the host
 * string is resolved by Tor, never by the device. The minimal
 * [Socks5] dialer in `:core:transport:api` does exactly that.
 */
@Singleton
class MoneroRpcClient @Inject constructor() {

    /**
     * Hits the daemon's `/get_info` endpoint and returns parsed
     * key fields. Caller supplies the SOCKS port (from
     * `TorBackend.socksPort.value`) so this class doesn't need a
     * dependency on TorBackend itself — keeps RPC testable in
     * isolation.
     *
     * Throws on any IO, SOCKS, or HTTP-level failure. Callers
     * (the [MoneroWalletService]) catch and round-robin to the
     * next node.
     */
    suspend fun getInfo(node: MoneroNode, torSocksPort: Int): NodeInfo = withContext(Dispatchers.IO) {
        val body = httpGetThroughTor(node, torSocksPort, path = "/get_info")
        // /get_info returns the daemon's "Core RPC" envelope, not
        // JSON-RPC. Top-level fields directly.
        val json = JSONObject(body)
        NodeInfo(
            height = json.optLong("height", -1L),
            targetHeight = json.optLong("target_height", -1L),
            synchronized = json.optBoolean("synchronized", false),
            outgoingConnections = json.optInt("outgoing_connections_count", 0),
            incomingConnections = json.optInt("incoming_connections_count", 0),
            nettype = json.optString("nettype", "unknown"),
            version = json.optString("version", "?"),
        )
    }

    /**
     * Minimal HTTP/1.1 GET driven by hand over the SOCKS-tunneled
     * socket. Monero's daemon-rpc uses keepalive-less plain HTTP on
     * the chosen port; we don't need a full HTTP client for the
     * three read-only methods this scaffold exposes. When the JNI
     * lib lands and we start hitting JSON-RPC POSTs in volume we'll
     * either swap to OkHttp-over-SOCKS-DOMAINNAME (via a custom
     * SocketFactory) or stay with this for the entire RPC surface.
     *
     * **Security caps.**
     *  - Path is fixed by the caller (no user-supplied URL). Hosts
     *    come from [MoneroNodeRegistry] which is hard-coded; user-
     *    added nodes will need their host/port validated against a
     *    `[a-zA-Z0-9.\-]` regex before reaching this method (no
     *    `\r\n` for header smuggling).
     *  - Response body is bounded by [MAX_RESPONSE_BYTES]. A
     *    hostile node that streams indefinitely is closed with an
     *    [IOException] — the round-robin then tries the next node.
     *  - Read timeout per byte is [DEFAULT_READ_TIMEOUT_MS]; a node
     *    that opens a connection and goes silent unblocks within
     *    that window.
     */
    private fun httpGetThroughTor(node: MoneroNode, torSocksPort: Int, path: String): String {
        val socket: Socket = Socks5.dial(
            torSocksPort = torSocksPort,
            host = node.host,
            port = node.port,
            connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
        )
        try {
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS
            val out = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
            out.write(
                "GET $path HTTP/1.1\r\n" +
                    "Host: ${node.host}:${node.port}\r\n" +
                    "User-Agent: Keystone/0.7.0\r\n" +
                    "Accept: application/json\r\n" +
                    "Connection: close\r\n" +
                    "\r\n",
            )
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            // Status line + headers
            val statusLine = reader.readLine() ?: throw IOException("RPC: empty response")
            val statusParts = statusLine.split(' ', limit = 3)
            if (statusParts.size < 2 || statusParts[0] != "HTTP/1.1") {
                throw IOException("RPC: malformed status line '$statusLine'")
            }
            val statusCode = statusParts[1].toIntOrNull()
                ?: throw IOException("RPC: non-numeric status '$statusLine'")
            if (statusCode !in 200..299) {
                throw IOException("RPC: $node returned HTTP $statusCode")
            }
            // Drain headers — we don't need any of them for the v0.7.0 slice.
            while (true) {
                val h = reader.readLine() ?: throw IOException("RPC: truncated headers")
                if (h.isEmpty()) break
            }
            // Read body to EOF, capped at MAX_RESPONSE_BYTES so a
            // hostile node can't OOM the wallet UI by streaming.
            val body = StringBuilder()
            val buf = CharArray(BODY_CHUNK_CHARS)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (body.length + n > MAX_RESPONSE_BYTES) {
                    throw IOException(
                        "RPC: $node response exceeded $MAX_RESPONSE_BYTES bytes; aborting",
                    )
                }
                body.append(buf, 0, n)
            }
            return body.toString()
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Parsed subset of `/get_info`. Daemon returns many more fields;
     * these are the ones the wallet UI displays today.
     */
    data class NodeInfo(
        val height: Long,
        val targetHeight: Long,
        val synchronized: Boolean,
        val outgoingConnections: Int,
        val incomingConnections: Int,
        val nettype: String,
        val version: String,
    )

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000
        private const val BODY_CHUNK_CHARS = 4096

        /**
         * Hard cap on response body size. `/get_info` is normally
         * well under 1 KB; the cap is set deliberately loose so an
         * upstream daemon adding fields doesn't accidentally trip
         * it, but tight enough that a hostile node can't stream
         * gigabytes into the wallet process.
         */
        private const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
