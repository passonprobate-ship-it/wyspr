package com.wyspr.core.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 dialer for Tor destinations.
 *
 * Tor's local SOCKS5 proxy speaks RFC 1928 with one quirk: the
 * `DOMAINNAME` address type (0x03) is the only way to ask Tor to
 * resolve a hidden-service address. Java's `Proxy.SOCKS` IPv6 fallback
 * doesn't satisfy this, so we drive the handshake by hand against a
 * raw socket to `127.0.0.1:torSocksPort`.
 *
 * Authentication is "no auth" (method 0x00) — Tor's local listener
 * doesn't require credentials. The host string is sent verbatim;
 * caller is responsible for not passing more than 255 bytes.
 *
 * The returned [Socket] is fully connected end-to-end: caller may
 * read/write to the destination directly. Closing the socket tears
 * the circuit down on Tor's side too.
 *
 * Used by:
 *  - `TorHiddenServiceTransport` in `:app` to dial peer `.onion:9091`
 *    for Wyspr-to-Wyspr Noise sessions.
 *  - `MoneroRpcClient` in `:feature:monero-wallet` to dial Monero
 *    remote-node RPC endpoints (clearnet or `.onion`) through Tor.
 */
object Socks5 {

    /**
     * Open a TCP stream through Tor to [host]:[port]. Blocking — caller
     * dispatches to IO. Throws on any protocol violation or connect
     * failure; caller is expected to catch and surface as a transport
     * error.
     */
    /**
     * Default for `postHandoffReadTimeoutMs`. A wedged Tor circuit
     * would otherwise pin a thread reading nothing forever — the read
     * would only return on socket close, which has no scheduler.
     */
    const val DEFAULT_POST_HANDOFF_READ_TIMEOUT_MS: Int = 60_000

    @Throws(IOException::class)
    fun dial(
        torSocksPort: Int,
        host: String,
        port: Int,
        connectTimeoutMs: Int = 30_000,
        /** Per-read timeout applied to the returned socket. Set to 0 to
         *  disable (caller takes responsibility for deadlines). */
        postHandoffReadTimeoutMs: Int = DEFAULT_POST_HANDOFF_READ_TIMEOUT_MS,
        /**
         * Optional Tor stream-isolation key. When set, sends RFC 1929
         * USERPASS auth with this key as the username; Tor's default
         * `IsolateSOCKSAuth` config then allocates a fresh circuit for
         * the dial instead of pooling onto a shared one. Useful when
         * repeated dials from the same process keep landing on a stuck
         * intro point — pass a fresh key per attempt to force Tor to
         * try a different rendezvous path. Tor doesn't actually
         * authenticate the username; any non-empty string works.
         */
        isolationKey: String? = null,
    ): Socket {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        require(hostBytes.size in 1..255) { "SOCKS5 DOMAINNAME must be 1..255 bytes" }
        require(port in 1..0xFFFF) { "port out of range" }

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", torSocksPort), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val out: OutputStream = socket.getOutputStream()
            val ins: InputStream = socket.getInputStream()

            // ---- Method negotiation (RFC 1928 §3) ----
            if (isolationKey == null) {
                // VER=5, NMETHODS=1, METHODS=[NO_AUTH]
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val mver = ins.read()
                val mmethod = ins.read()
                if (mver != 0x05 || mmethod != 0x00) {
                    throw IOException("SOCKS5: method negotiation failed (ver=$mver method=$mmethod)")
                }
            } else {
                // VER=5, NMETHODS=1, METHODS=[USERPASS]
                out.write(byteArrayOf(0x05, 0x01, 0x02))
                out.flush()
                val mver = ins.read()
                val mmethod = ins.read()
                if (mver != 0x05 || mmethod != 0x02) {
                    throw IOException("SOCKS5: server refused USERPASS auth (ver=$mver method=$mmethod)")
                }
                // RFC 1929 USERPASS sub-negotiation. Tor doesn't check
                // the password; any non-empty string works. Use a
                // single-byte password to minimise wire bytes.
                val userBytes = isolationKey.encodeToByteArray()
                require(userBytes.size in 1..255) { "isolationKey must be 1..255 bytes" }
                out.write(0x01)                  // RFC 1929 VER
                out.write(userBytes.size)
                out.write(userBytes)
                out.write(0x01)                  // PLEN=1
                out.write(0x00)                  // PWD=0x00
                out.flush()
                val aver = ins.read()
                val astatus = ins.read()
                if (aver != 0x01 || astatus != 0x00) {
                    throw IOException("SOCKS5 USERPASS auth failed (ver=$aver status=$astatus)")
                }
            }

            // ---- CONNECT request (RFC 1928 §4) ----
            // VER=5, CMD=CONNECT, RSV=0, ATYP=DOMAINNAME, LEN=N, HOST, PORT(be)
            out.write(0x05) // VER
            out.write(0x01) // CMD=CONNECT
            out.write(0x00) // RSV
            out.write(0x03) // ATYP=DOMAINNAME
            out.write(hostBytes.size)
            out.write(hostBytes)
            out.write((port ushr 8) and 0xFF)
            out.write(port and 0xFF)
            out.flush()

            // ---- CONNECT reply (RFC 1928 §6) ----
            val rver = ins.read()
            val rep = ins.read()
            val rsv = ins.read()
            val atyp = ins.read()
            if (rver != 0x05 || rsv != 0x00) {
                throw IOException("SOCKS5: bad reply header ver=$rver rsv=$rsv")
            }
            if (rep != 0x00) {
                throw IOException("SOCKS5 CONNECT failed: reply code $rep (${replyName(rep)})")
            }
            // Drain BND.ADDR + BND.PORT — required by spec even on
            // success so the next bytes on the wire are application
            // data. Length depends on the address type Tor echoes.
            when (atyp) {
                0x01 -> { // IPv4
                    val buf = ByteArray(4); readFully(ins, buf, "BND IPv4")
                }
                0x03 -> { // DOMAINNAME
                    val len = ins.read()
                    if (len < 0) throw IOException("SOCKS5: truncated BND domain length")
                    val buf = ByteArray(len); readFully(ins, buf, "BND domain")
                }
                0x04 -> { // IPv6
                    val buf = ByteArray(16); readFully(ins, buf, "BND IPv6")
                }
                else -> throw IOException("SOCKS5: unknown ATYP $atyp in reply")
            }
            val portBuf = ByteArray(2); readFully(ins, portBuf, "BND port")

            // Once we're past the reply, the socket is a clean
            // bidirectional stream to the destination. Keep a non-zero
            // soTimeout by default so a wedged Tor circuit can't pin a
            // thread on stream reads forever; callers that have their
            // own deadline machinery can pass 0 to disable.
            socket.soTimeout = postHandoffReadTimeoutMs.coerceAtLeast(0)
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, label: String) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("SOCKS5: EOF while reading $label")
            off += n
        }
    }

    /**
     * RFC 1928 §6 reply-code names; helpful for diagnostics when Tor
     * refuses to dial a destination (e.g. a `.onion` descriptor hasn't
     * propagated to the directory hash ring yet, or the remote daemon
     * is just down).
     */
    private fun replyName(rep: Int): String = when (rep) {
        0x01 -> "general SOCKS server failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown $rep"
    }
}
