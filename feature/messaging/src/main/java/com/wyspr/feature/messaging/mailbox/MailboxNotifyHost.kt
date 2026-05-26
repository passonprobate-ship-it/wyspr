package com.wyspr.feature.messaging.mailbox

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.identity.PeerKey
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.ui.settings.MailboxSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server side of Sprint 3's mailbox push-notify channel.
 *
 * Listens on `127.0.0.1:{[TorBackend.MAILBOX_NOTIFY_TARGET_PORT]}`,
 * which Tor's HiddenServicePort 9093 forwards onion traffic to.
 * Recipients SOCKS5-dial `<host_onion>:9093`, send a signed
 * [MailboxNotifyFrame.Subscribe], and then receive
 * [MailboxNotifyFrame.Notify] frames whenever this host stores a
 * fresh envelope for them.
 *
 * Lifecycle: collects [MailboxSettings.hostEnabled] and starts/stops
 * the listener accordingly. We deliberately bind regardless of Tor
 * state — the listener is harmless when Tor isn't publishing yet,
 * and ties of "tcp socket open" to "tor up" would just add latency
 * on every Tor restart.
 *
 * Auth model: the Subscribe frame's Ed25519 signature proves the
 * subscriber owns the claimed `ownerPub`. Additionally we check the
 * host has a known [MailboxBinding] for that owner — preventing a
 * trusted-but-unrelated peer from subscribing for push notifications
 * on someone else's mailbox traffic.
 *
 * The IPv4-loopback bind (rather than `getLoopbackAddress()`) is the
 * same fix we made in `TorHiddenServiceTransport` on 2026-05-22 —
 * Tor forwards to 127.0.0.1, period. `::1` listeners are invisible
 * to it.
 */
@Singleton
class MailboxNotifyHost @Inject constructor(
    private val settings: MailboxSettings,
    private val keystore: KeystoreManager,
    private val sodium: LazySodiumAndroid,
    private val bindingService: MailboxBindingService,
    private val mailboxHost: MailboxHost,
) {

    /**
     * Long-running scope tied to the singleton lifetime. Not a child
     * of any caller — we want the listener to outlive whichever
     * ViewModel happened to flip `hostEnabled`. Cancelled never; the
     * app dying takes the scope with it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val startMutex = Mutex()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null
    @Volatile private var fanoutJob: Job? = null

    /**
     * Active subscriber connections keyed by owner pub. A single
     * recipient can hold multiple subscriptions (multiple phones
     * with the same identity, retried connections, etc.) — we push
     * to all of them and clean up on individual failures.
     *
     * Stored in a [ConcurrentHashMap] because the accept loop, the
     * stored-events fan-out, and the per-connection writers can all
     * race. The contained sets are also concurrent-safe (Collections.newSetFromMap).
     */
    private val subscribers: ConcurrentHashMap<PeerKey, MutableSet<Subscriber>> =
        ConcurrentHashMap()

    init {
        // Auto-start on hostEnabled true, auto-stop on false. Single
        // coroutine on the singleton scope; distinctUntilChanged so
        // a redundant set(true) doesn't re-bind a healthy listener.
        // StateFlow self-deduplicates — no need for distinctUntilChanged.
        scope.launch {
            settings.hostEnabled.collect { enabled ->
                if (enabled) startInternal() else stopInternal()
            }
        }
    }

    private suspend fun startInternal() { startMutex.withLock {
        if (server != null) return@withLock
        val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val sock = withContext(Dispatchers.IO) {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ipv4Loopback, TorBackend.MAILBOX_NOTIFY_TARGET_PORT))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "start: bind to 127.0.0.1:${TorBackend.MAILBOX_NOTIFY_TARGET_PORT} failed", t)
                null
            }
        } ?: return@withLock
        server = sock
        Log.i(TAG, "start: listening on 127.0.0.1:${TorBackend.MAILBOX_NOTIFY_TARGET_PORT}")

        acceptJob = scope.launch { runAcceptLoop(sock) }
        fanoutJob = scope.launch { runStoredEventsFanout() }
    } }

    private suspend fun stopInternal() { startMutex.withLock {
        val sock = server ?: return@withLock
        server = null
        // Cancel jobs first so the accept loop notices the close
        // promptly via the socket exception instead of looping.
        acceptJob?.cancel()
        fanoutJob?.cancel()
        acceptJob = null
        fanoutJob = null
        // Close every active subscriber connection so writers unblock
        // and exit cleanly. Iterating a snapshot is safe; the
        // per-connection finally also removes from the map.
        val toClose = subscribers.values.flatten()
        subscribers.clear()
        for (sub in toClose) sub.close()
        withContext(Dispatchers.IO) {
            runCatching { sock.close() }
        }
        Log.i(TAG, "stop: listener closed (${toClose.size} subscriber(s) dropped)")
    } }

    private suspend fun runAcceptLoop(sock: ServerSocket) {
        while (true) {
            val client = try {
                withContext(Dispatchers.IO) { sock.accept() }
            } catch (t: Throwable) {
                if (server == null) return // graceful stop
                Log.w(TAG, "accept failed; pausing 1s", t)
                kotlinx.coroutines.delay(1_000)
                continue
            }
            // Per-connection coroutine. Failure on one connection
            // never blocks the accept loop or any other subscriber.
            scope.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        var registered: Subscriber? = null
        try {
            // Disable Nagle: Notify frames are 3 bytes and we want them
            // out the door immediately when the host stores an
            // envelope. The Tor circuit adds enough latency that
            // batching would hurt UX, not save bandwidth.
            runCatching { socket.tcpNoDelay = true }
            socket.soTimeout = SUBSCRIBE_READ_TIMEOUT_MS

            val frame = withTimeoutOrNull(SUBSCRIBE_READ_TIMEOUT_MS.toLong()) {
                withContext(Dispatchers.IO) { readFrame(socket) }
            } ?: run {
                Log.d(TAG, "subscriber timed out before sending Subscribe; dropping")
                return
            }

            val subscribe = frame as? MailboxNotifyFrame.Subscribe ?: run {
                Log.w(TAG, "first frame was ${frame::class.simpleName}, not Subscribe; dropping")
                return
            }
            if (!validateSubscribe(subscribe)) return

            // Clear soTimeout for the persistent-write phase. Reads
            // wait for the peer to close (eof returns -1 from the
            // read loop, ending it).
            socket.soTimeout = 0
            val sub = Subscriber(
                ownerPub = PublicKey(subscribe.ownerPub),
                socket = socket,
            )
            if (!register(sub)) {
                Log.w(TAG, "subscriber rejected (cap reached); dropping connection")
                return
            }
            registered = sub

            Log.d(
                TAG,
                "subscribed peer=${shortHex(subscribe.ownerPub)} " +
                    "(total subs for peer: ${subscribers[PeerKey(subscribe.ownerPub)]?.size ?: 0})",
            )

            // Wait for the peer to close. We don't expect any further
            // frames from the recipient; if they want to renew they
            // disconnect and reconnect.
            awaitPeerClose(socket)
        } catch (t: Throwable) {
            Log.d(TAG, "subscriber handler ended: ${t::class.simpleName}: ${t.message}")
        } finally {
            registered?.let { deregister(it) }
            runCatching { socket.close() }
        }
    }

    private suspend fun validateSubscribe(s: MailboxNotifyFrame.Subscribe): Boolean {
        val now = System.currentTimeMillis() / 1000
        val skew = MailboxNotifyFrame.CLOCK_SKEW_SECONDS
        if (s.timestampSeconds !in (now - skew)..(now + skew)) {
            Log.w(TAG, "subscribe: timestamp ${s.timestampSeconds} outside ±${skew}s window; rejecting")
            return false
        }
        val signed = s.signedBytes()
        if (!sodium.cryptoSignVerifyDetached(s.signature, signed, signed.size, s.ownerPub)) {
            Log.w(TAG, "subscribe: signature verify failed for peer=${shortHex(s.ownerPub)}")
            return false
        }
        // Confirm we actually hold a binding for this owner. Without
        // this check anyone with our .onion could tie up subscriber
        // slots without ever having delegated us as their mailbox.
        val ownerKey = PublicKey(s.ownerPub)
        val bindings = bindingService.forOwner(ownerKey)
        if (bindings.isEmpty()) {
            Log.w(TAG, "subscribe: no known binding from peer=${shortHex(s.ownerPub)}; rejecting")
            return false
        }
        return true
    }

    private fun register(sub: Subscriber): Boolean {
        // Global subscriber cap — resource exhaustion defence.
        val totalCount = subscribers.values.sumOf { it.size }
        if (totalCount >= MAX_SUBSCRIBERS_TOTAL) {
            Log.w(TAG, "register: rejecting subscriber — total cap ($MAX_SUBSCRIBERS_TOTAL) reached")
            return false
        }
        subscribers.compute(PeerKey(sub.ownerPub.bytes)) { _, existing ->
            val set = existing ?: java.util.Collections.newSetFromMap(ConcurrentHashMap())
            // Per-owner cap — close the oldest connection for this owner
            // before adding the new one so a single owner can't exhaust
            // the global budget.
            if (set.size >= MAX_SUBSCRIBERS_PER_OWNER) {
                val oldest = set.firstOrNull()
                if (oldest != null) {
                    Log.d(TAG, "register: per-owner cap ($MAX_SUBSCRIBERS_PER_OWNER) reached, closing oldest")
                    set.remove(oldest)
                    oldest.close()
                }
            }
            set.also { it.add(sub) }
        }
        return true
    }

    private fun deregister(sub: Subscriber) {
        subscribers.compute(PeerKey(sub.ownerPub.bytes)) { _, existing ->
            existing?.also { it.remove(sub) }?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Collect [MailboxHost.storedEvents] and push a [MailboxNotifyFrame.Notify]
     * to every subscriber registered for the stored envelope's recipient.
     */
    private suspend fun runStoredEventsFanout() {
        val notifyBytes = framedBytes(MailboxNotifyFrame.Notify.wireBytes())
        mailboxHost.storedEvents.collect { toPub ->
            val key = PeerKey(toPub.bytes)
            val snapshot = subscribers[key]?.toList() ?: emptyList()
            if (snapshot.isEmpty()) return@collect
            Log.d(TAG, "fanout: ${snapshot.size} subscriber(s) for peer=${shortHex(toPub.bytes)}")
            for (sub in snapshot) {
                try {
                    withContext(Dispatchers.IO) {
                        sub.socket.getOutputStream().apply {
                            write(notifyBytes)
                            flush()
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "fanout: write failed to a subscriber (${t::class.simpleName}); dropping")
                    deregister(sub)
                    sub.close()
                }
            }
        }
    }

    /** Block (on IO) until the peer's half of the socket closes (read returns -1). */
    private suspend fun awaitPeerClose(socket: Socket) = withContext(Dispatchers.IO) {
        val ins = socket.getInputStream()
        val buf = ByteArray(64)
        while (true) {
            val n = try {
                ins.read(buf)
            } catch (t: IOException) {
                return@withContext // socket closed
            }
            if (n < 0) return@withContext // EOF
            // Discard any post-subscribe payload silently — v1 has no
            // back-channel from recipient to host.
        }
    }

    /** Read one length-prefixed CBOR frame from [socket]. */
    private fun readFrame(socket: Socket): MailboxNotifyFrame {
        val ins = DataInputStream(socket.getInputStream())
        val len = ins.readUnsignedShort()
        if (len > MailboxNotifyFrame.MAX_FRAME_BYTES) {
            throw IOException("frame $len > MAX_FRAME_BYTES ${MailboxNotifyFrame.MAX_FRAME_BYTES}")
        }
        val body = ByteArray(len)
        ins.readFully(body)
        return MailboxNotifyFrame.fromWire(body)
    }

    /** Prepend `u16 BE` length header. */
    private fun framedBytes(body: ByteArray): ByteArray {
        require(body.size <= MailboxNotifyFrame.MAX_FRAME_BYTES)
        val out = ByteArray(body.size + 2)
        out[0] = ((body.size ushr 8) and 0xFF).toByte()
        out[1] = (body.size and 0xFF).toByte()
        body.copyInto(out, 2)
        return out
    }

    private data class Subscriber(
        val ownerPub: PublicKey,
        val socket: Socket,
    ) {
        fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        private const val TAG = "MailboxNotifyHost"
        private const val SUBSCRIBE_READ_TIMEOUT_MS: Int = 10_000

        /** Maximum concurrent subscribers across all owners. */
        private const val MAX_SUBSCRIBERS_TOTAL = 100
        /** Maximum concurrent subscribers for a single owner. Oldest
         *  is evicted when exceeded. */
        private const val MAX_SUBSCRIBERS_PER_OWNER = 5

        private fun shortHex(bytes: ByteArray): String =
            bytes.take(4).joinToString("") { "%02x".format(it) }
    }
}
