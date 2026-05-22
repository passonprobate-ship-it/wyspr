package com.keystone.feature.messaging.mailbox

import android.util.Log
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.transport.Socks5
import com.keystone.core.transport.TorBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client side of Sprint 3's mailbox push-notify channel.
 *
 * Dials the recipient-owned binding's mailbox host
 * (`<binding.mailboxOnion>:9093`) over Tor, sends a signed
 * [MailboxNotifyFrame.Subscribe], and then suspends reading
 * [MailboxNotifyFrame.Notify] frames. Each Notify is converted to
 * an `onNotify()` callback so the caller can trigger an immediate
 * `MessageSyncService.runOnce()` against the host.
 *
 * Stateless and one-shot — `subscribe()` blocks until the
 * connection closes (peer disconnect, IO error, cancellation).
 * Callers are responsible for retry/backoff and for stopping the
 * subscription when the user leaves the conversation.
 *
 * Why no built-in retry: the desired retry policy depends on the
 * caller's context (in-conversation vs background WorkManager
 * tick). Keeping this dumb means the existing 8s auto-sync still
 * acts as the safety net for any subscription that drops.
 */
@Singleton
class MailboxNotifyClient @Inject constructor(
    private val keystore: KeystoreManager,
    private val torBackend: TorBackend,
) {

    /**
     * Open a subscription to [binding]'s mailbox host. Sends the
     * Subscribe frame, then loops on Notify frames calling
     * [onNotify] for each. Returns when the connection closes
     * cleanly or throws on IO / cancellation.
     *
     * Caller dispatches; this method itself uses [Dispatchers.IO]
     * for the blocking socket operations. Cancellation propagates
     * through `Socket.close()`.
     *
     * @return number of Notify frames received before the
     *         connection ended. Useful for telemetry / "did the
     *         subscription do any good" tracking.
     */
    suspend fun subscribe(
        binding: MailboxBinding,
        onNotify: suspend () -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val onion = binding.mailboxOnion ?: error(
            "MailboxNotifyClient: binding has no mailboxOnion (BLE-only mailbox); " +
                "push-notify requires a Tor-reachable host.",
        )
        val socksPort = torBackend.socksPort.value
            ?: error("MailboxNotifyClient: Tor SOCKS port unavailable")

        Log.d(TAG, "dialing $onion:${TorBackend.MAILBOX_NOTIFY_TARGET_PORT} via SOCKS :$socksPort (JDK proxy)")
        // Use JDK's built-in SOCKS5 client rather than our hand-rolled
        // [Socks5.dial]. Empirically on 2026-05-22, our impl gets
        // reply-code-4 on every attempt to port 9093 while curl on
        // the same SOCKS proxy succeeds — the byte format looks
        // identical on review, but something in the JDK path that
        // curl mirrors makes Tor happy. Use the JDK to sidestep
        // the mystery for now.
        //
        // `createUnresolved` is critical: Java's SOCKS impl only
        // sends DOMAINNAME ATYP when the address is unresolved.
        // Otherwise it tries DNS first, which fails for `.onion`.
        var socket: java.net.Socket? = null
        var lastErr: Throwable? = null
        repeat(DIAL_RETRY_COUNT) { attempt ->
            try {
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", socksPort),
                )
                val s = java.net.Socket(proxy)
                s.connect(
                    java.net.InetSocketAddress.createUnresolved(
                        onion, TorBackend.MAILBOX_NOTIFY_TARGET_PORT,
                    ),
                    30_000,
                )
                socket = s
                return@repeat
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastErr = t
                Log.d(TAG, "dial attempt ${attempt + 1}/$DIAL_RETRY_COUNT to $onion failed: ${t::class.simpleName}: ${t.message}")
                if (attempt < DIAL_RETRY_COUNT - 1) {
                    kotlinx.coroutines.delay(DIAL_RETRY_DELAY_MS)
                }
            }
        }
        val sock = socket ?: run {
            Log.d(TAG, "dial $onion failed after $DIAL_RETRY_COUNT attempts: ${lastErr?.javaClass?.simpleName}: ${lastErr?.message}")
            return@withContext 0
        }

        try {
            runCatching { sock.tcpNoDelay = true }
            // Sign + send Subscribe immediately. KeystoreManager.sign
            // returns the raw 64-byte Ed25519 sig.
            val ownIdentity = keystore.loadOrCreateIdentityKey()
            val timestamp = System.currentTimeMillis() / 1000
            val signed = MailboxNotifyFrame.signedBytesFor(ownIdentity.publicKey, timestamp)
            val signature = keystore.sign(signed)
            val sub = MailboxNotifyFrame.Subscribe(
                ownerPub = ownIdentity.publicKey,
                timestampSeconds = timestamp,
                signature = signature,
            )
            writeFrame(sock, sub.wireBytes())

            // Block reading Notify frames until the socket closes.
            var notifies = 0
            val ins = DataInputStream(sock.getInputStream())
            while (true) {
                ensureActive()
                val len = try {
                    ins.readUnsignedShort()
                } catch (_: java.io.EOFException) {
                    break
                } catch (t: IOException) {
                    Log.d(TAG, "read failed: ${t::class.simpleName}: ${t.message}")
                    break
                }
                if (len > MailboxNotifyFrame.MAX_FRAME_BYTES) {
                    Log.w(TAG, "host sent oversized frame ($len bytes); dropping")
                    break
                }
                val body = ByteArray(len)
                try {
                    ins.readFully(body)
                } catch (_: java.io.EOFException) {
                    break
                }
                val frame = try {
                    MailboxNotifyFrame.fromWire(body)
                } catch (t: Throwable) {
                    Log.w(TAG, "host sent malformed frame; dropping: ${t::class.simpleName}")
                    break
                }
                when (frame) {
                    is MailboxNotifyFrame.Notify -> {
                        notifies += 1
                        Log.d(TAG, "Notify received (#$notifies for $onion)")
                        try {
                            onNotify()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "onNotify callback threw; continuing", t)
                        }
                    }
                    is MailboxNotifyFrame.Subscribe -> {
                        // Hosts don't send Subscribe back. Drop the
                        // connection — peer is confused or malicious.
                        Log.w(TAG, "host sent Subscribe frame; closing")
                        break
                    }
                }
            }
            Log.d(TAG, "subscription to $onion ended after $notifies notify(es)")
            notifies
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun writeFrame(socket: Socket, body: ByteArray) {
        require(body.size <= MailboxNotifyFrame.MAX_FRAME_BYTES) {
            "frame ${body.size} > ${MailboxNotifyFrame.MAX_FRAME_BYTES}"
        }
        val out = socket.getOutputStream()
        out.write((body.size ushr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(body)
        out.flush()
    }

    private companion object {
        private const val TAG = "MailboxNotifyClient"

        /**
         * Number of dial attempts per `subscribe()` call. Tor's HS
         * rendezvous is stochastic — observed reply-code-4 ~70% of
         * the time on first attempt, ~0% by attempt 3. Curl on the
         * same SOCKS proxy succeeds first time, suggesting Tor uses
         * different intro-point selection across SOCKS streams.
         */
        private const val DIAL_RETRY_COUNT: Int = 3

        /** Pause between in-call dial retries. Lets Tor pick a fresh circuit. */
        private const val DIAL_RETRY_DELAY_MS: Long = 1_500L
    }
}
