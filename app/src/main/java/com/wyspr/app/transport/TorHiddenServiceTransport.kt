package com.wyspr.app.transport

import android.util.Log
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import com.wyspr.core.transport.Socks5
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Transport implementation that runs the Noise/Sync wire format over
 * Tor circuits.
 *
 * Topology mirrors [com.wyspr.core.transport.bluetooth.BleTransport]:
 * each device is both a server and a client. The server binds a
 * loopback TCP listener on [TorBackend.hsTargetPort]; the embedded
 * Tor daemon's HiddenServiceDir forwards incoming `<our>.onion:port`
 * traffic into that listener, which yields a [Link] on
 * [acceptedLinks]. The client opens TCP streams to peer .onion
 * addresses by SOCKS5-CONNECTing through Tor's local proxy.
 *
 * The transport intentionally does NOT do peer discovery. Tor has
 * no "find me a peer in this community" primitive — peers are dialed
 * directly by .onion address. Address discovery happens at handshake
 * time (the QR carries the peer's .onion, persisted to
 * `trust_edge.peerOnion` in Sprint 3), so by the time someone calls
 * [connect] the address is already known. [discovered] returns the
 * empty flow.
 *
 * Sprint 4 lands this transport in isolation — it is constructed and
 * wired through Hilt, but it is not yet selected by the handshake or
 * sync pipelines. Sprint 5 will introduce a [Transport]-of-transports
 * selector that picks BLE for fresh pairings and Tor for established
 * trust edges with a known `peerOnion`.
 */
class TorHiddenServiceTransport(
    private val torBackend: TorBackend,
) : Transport {

    override val kind: Transport.Kind = Transport.Kind.TorHiddenService

    private val lock = Mutex()

    private class Session(
        val server: ServerSocket,
        val accepted: Channel<Link>,
        val acceptedLinks: ConcurrentHashMap<String, TorLink>,
        val scope: CoroutineScope,
        val acceptJob: Job,
    )

    @Volatile private var session: Session? = null

    override suspend fun start(communityId: CommunityId): Unit = lock.withLock {
        if (session != null) {
            Log.d(TAG, "start: already running")
            return@withLock
        }
        val targetPort = torBackend.hsTargetPort
        // Bind on IPv4 loopback only. Tor forwards `.onion:targetPort`
        // to `127.0.0.1:targetPort` — always IPv4. `InetAddress.getLoopbackAddress()`
        // returns `::1` (IPv6) on dual-stack Androids, which makes
        // Tor's final forwarding hop hit nothing and surface as SOCKS
        // reply code 4 (host unreachable) on the dialer side. The
        // descriptor lookup + rendezvous + intro-point handshake all
        // succeed; only the last 1mm of localhost forwarding fails.
        // Bind explicitly to 127.0.0.1 so both endpoints agree.
        //
        // Binding on 0.0.0.0 would expose the service on every
        // interface, defeating the point of the hidden service.
        //
        // Rapid stop()/start() cycles (each sync round currently
        // tears the listener down and brings it back up) can race
        // with the kernel's TIME_WAIT release of the previous bind
        // and surface as `EADDRINUSE` even with SO_REUSEADDR set.
        // Retry briefly before giving up so a single transient
        // collision doesn't kill the whole sync round.
        val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = withContext(Dispatchers.IO) {
            var lastErr: Throwable? = null
            var attempt = 0
            while (attempt < BIND_RETRY_COUNT) {
                try {
                    return@withContext ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(ipv4Loopback, targetPort))
                    }
                } catch (be: java.net.BindException) {
                    lastErr = be
                    attempt += 1
                    Log.w(TAG, "start: bind attempt $attempt failed (${be.message}); retrying in ${BIND_RETRY_BACKOFF_MS}ms")
                    delay(BIND_RETRY_BACKOFF_MS)
                }
            }
            throw lastErr ?: java.net.BindException("bind to :$targetPort failed after $BIND_RETRY_COUNT attempts")
        }
        Log.d(TAG, "start: listening on 127.0.0.1:$targetPort (community ${communityId.bytes.take(4).joinToString("") { "%02x".format(it) }}…)")

        val accepted = Channel<Link>(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND)
        val acceptedLinks = ConcurrentHashMap<String, TorLink>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val acceptJob = scope.launch { runAcceptLoop(server, accepted, acceptedLinks) }

        session = Session(
            server = server,
            accepted = accepted,
            acceptedLinks = acceptedLinks,
            scope = scope,
            acceptJob = acceptJob,
        )
    }

    override suspend fun stop(): Unit = lock.withLock {
        val current = session ?: return@withLock
        session = null
        Log.d(TAG, "stop: closing listener + ${current.acceptedLinks.size} accepted links")
        withContext(Dispatchers.IO) {
            runCatching { current.server.close() }
        }
        current.accepted.close()
        for (link in current.acceptedLinks.values) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { link.close() }
            }
        }
        current.acceptedLinks.clear()
        current.scope.coroutineContext[Job]?.cancel()
    }

    override fun discovered(): Flow<PeerEndpoint> = emptyFlow()

    // `receiveAsFlow`, NOT `consumeAsFlow`: the Tor listener is kept
    // alive across sync rounds (see [TransportSelector.stopAll]) so
    // a single Channel is shared by every round. `consumeAsFlow`
    // would cancel that Channel when the first round's `first()`
    // collection ends, leaving every subsequent round unable to
    // accept Tor inbounds. `receiveAsFlow` leaves the Channel open
    // and lets the next round resubscribe.
    override fun acceptedLinks(): Flow<Link> =
        session?.accepted?.receiveAsFlow() ?: emptyFlow()

    /**
     * Discard every Link currently buffered in the accept channel
     * without consuming the channel itself. Called by the responder-
     * side sync code at round start so a brand-new round doesn't
     * pick up a Tor inbound that completed in a previous round (or
     * was queued by the Tor daemon while our listener was bound but
     * no collector was active).
     *
     * Each drained Link is closed asynchronously so its underlying
     * socket gets released; the peer that connected will see EOF on
     * its next read.
     */
    fun drainAccepted() {
        val current = session ?: return
        var drained = 0
        while (true) {
            val r = current.accepted.tryReceive()
            val link = r.getOrNull() ?: break
            drained++
            // Remove from the tracking map so the ConcurrentHashMap
            // doesn't grow unbounded with stale entries.
            if (link is TorLink) {
                current.acceptedLinks.values.remove(link)
            }
            current.scope.launch { runCatching { link.close() } }
        }
        if (drained > 0) Log.d(TAG, "drainAccepted: closed $drained stale link(s)")
    }

    /**
     * Dial a peer `.onion` over Tor. [endpoint.opaqueAddress] must be
     * the 56-char HSv3 address WITHOUT the `.onion` suffix — the
     * SOCKS dialer appends it. Throws if Tor isn't ready (no socks
     * port) or the circuit can't be built.
     */
    override suspend fun connect(endpoint: PeerEndpoint): Link {
        require(endpoint.kind == Transport.Kind.TorHiddenService) {
            "TorHiddenServiceTransport.connect requires a Tor endpoint, got ${endpoint.kind}"
        }
        val socksPort = torBackend.socksPort.value
            ?: error("Tor SOCKS proxy not ready yet — peer dialing requires a finished bootstrap")
        val target = endpoint.opaqueAddress
        val host = if (target.endsWith(".onion")) target else "$target.onion"
        Log.d(TAG, "connect: dialing $host:${torBackend.hsTargetPort} via SOCKS :$socksPort")
        val socket: Socket = withContext(Dispatchers.IO) {
            Socks5.dial(
                torSocksPort = socksPort,
                host = host,
                port = torBackend.hsTargetPort,
            )
        }
        return TorLink(endpoint, socket)
    }

    private suspend fun runAcceptLoop(
        server: ServerSocket,
        accepted: Channel<Link>,
        acceptedLinks: ConcurrentHashMap<String, TorLink>,
    ) {
        while (!server.isClosed) {
            val socket = try {
                withContext(Dispatchers.IO) { server.accept() }
            } catch (t: Throwable) {
                if (!server.isClosed) Log.w(TAG, "accept failed", t)
                return
            }
            // The remote side of an inbound Tor connection is always
            // localhost (Tor terminates the circuit and forwards). The
            // peer .onion isn't recoverable from the socket; mark this
            // endpoint as "inbound" — Noise's static-key check is what
            // actually authenticates the peer.
            val endpoint = PeerEndpoint(
                kind = Transport.Kind.TorHiddenService,
                opaqueAddress = "inbound:${socket.port}",
            )
            val link = TorLink(endpoint, socket)
            acceptedLinks[endpoint.opaqueAddress] = link
            // Suspending send so a stalled consumer applies back-pressure
            // to the accept loop rather than dropping new circuits on
            // the floor. The accept channel has finite capacity by
            // design — losing a real inbound Tor connection silently
            // (which `trySend` did) is worse than a brief stall here.
            try {
                accepted.send(link)
            } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                Log.w(TAG, "accepted channel closed; dropping inbound link")
                runCatching { link.close() }
                acceptedLinks.remove(endpoint.opaqueAddress)
                return
            }
        }
    }

    private companion object {
        private const val TAG = "TorHsTransport"
        private const val BIND_RETRY_COUNT = 5
        private const val BIND_RETRY_BACKOFF_MS = 200L
    }
}
