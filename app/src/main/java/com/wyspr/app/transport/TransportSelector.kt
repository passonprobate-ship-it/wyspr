package com.wyspr.app.transport

import android.util.Log
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import com.wyspr.core.transport.SyncTransportFacade
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.transport.Transport
import com.wyspr.core.transport.bluetooth.BleTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Façade that owns the BLE and Tor transports and the trust-edge
 * database lookups they share. Higher layers (sync engine, future
 * marketplace sync) consume this rather than reaching for one
 * transport directly so both wires get exercised symmetrically.
 *
 * Responsibilities:
 *   - Start/stop both transports together for a community.
 *   - Merge the accept-side flow so a responder picks up inbound
 *     traffic from either radio.
 *   - Resolve `peerOnion` for any trust edge involving the local
 *     identity, handling both Inviter-side `(local, peer)` and
 *     Invitee-side `(peer, local)` storage.
 *   - Dial the first known `.onion` to opportunistically use Tor
 *     when a peer's address has been exchanged.
 *
 * BLE remains the only viable transport for fresh pairings (no
 * `.onion` known yet) and for offline / radio-only situations.
 * Tor is layered alongside it, never replacing it.
 */
class TransportSelector(
    private val torTransport: TorHiddenServiceTransport,
    private val bleTransport: BleTransport,
    private val torBackend: TorBackend,
    private val database: WysprDatabase,
) : SyncTransportFacade {

    override suspend fun startAll(communityId: CommunityId) {
        // [TorHsTransport.start] is idempotent — the second call within
        // a session returns early if the listener is already up. We
        // exploit that to keep the loopback listener bound across sync
        // rounds (see [stopAll]) so we don't fight kernel port-reuse
        // delays on every retry.
        //
        // Each transport's start is independently fallible — the
        // common case where one transport is not viable (BLE radio
        // off; Tor still bootstrapping; permissions denied for one
        // radio) MUST NOT prevent the other transport from running.
        // Otherwise a user who's deliberately running over Tor alone
        // (BLE off for stealth, or just out of BLE range and on the
        // internet) gets no delivery at all because the BLE start
        // threw before the Tor leg could be exercised.
        runCatching { torTransport.start(communityId) }
            .onFailure { Log.w(TAG, "Tor transport start failed: ${it::class.simpleName}: ${it.message}") }
        runCatching { bleTransport.start(communityId) }
            .onFailure { Log.w(TAG, "BLE transport start failed: ${it::class.simpleName}: ${it.message}") }
    }

    override suspend fun stopAll() {
        // Intentionally leave [torTransport] running. Per-round teardown
        // is what produced the EADDRINUSE bind storm: the OS kept
        // 127.0.0.1:9091 reserved for ~10s after [ServerSocket.close],
        // even with SO_REUSEADDR set, presumably because Tor itself
        // (or its forwarding rules) holds a transient reference to the
        // forwarded socket. The loopback listener is cheap to keep
        // alive — inbound connections from the .onion forwarder simply
        // queue on the accept channel until the next sync round
        // consumes them via [acceptedLinks]. The BLE radio is the
        // expensive one and continues to start/stop per round.
        runCatching { bleTransport.stop() }
    }

    fun discoveredPeers(): Flow<PeerEndpoint> =
        merge(torTransport.discovered(), bleTransport.discovered())

    /**
     * Inbound links from either radio. Responder-side sync collects
     * `.first()` here; whichever transport delivered the peer wins.
     */
    override fun acceptedLinks(): Flow<Link> =
        merge(torTransport.acceptedLinks(), bleTransport.acceptedLinks())
            .onEach { Log.d(TAG, "acceptedLinks: inbound link arrived") }

    /**
     * Drop any inbound Links sitting in the Tor accept channel from
     * before this round started. BLE doesn't need this — its
     * transport restarts every round so its accept channel is always
     * fresh.
     */
    override fun drainStaleAccepted() {
        torTransport.drainAccepted()
    }

    /**
     * Wait for a BLE peer to advertise, then connect to it. The two
     * BleTransport calls are kept as one method on the facade because
     * the result is opaque to the caller — either we have a Link or
     * we don't.
     */
    override suspend fun bleDiscoverAndConnect(): Link {
        // When the BLE radio is off (or BleTransport.start() failed for
        // any reason — permissions, missing adapter), [discovered()]
        // returns an empty Flow. Calling .first() on an empty Flow
        // throws NoSuchElementException immediately, which races
        // ahead of the Tor dial branch in [MessageSyncService.dialOnly]
        // and propagates out through `select.onAwait`, failing the
        // whole round before Tor has a chance. Park here instead —
        // the race naturally picks the Tor branch if Tor succeeds,
        // and cancels us when it does.
        if (!bleTransport.isBluetoothReady) {
            Log.d(TAG, "bleDiscoverAndConnect: BLE radio not ready, parking branch")
            awaitCancellation()
        }
        val endpoint = bleTransport.discovered().first()
        Log.d(TAG, "bleDiscoverAndConnect: discovered ${endpoint.opaqueAddress}, dialing")
        return try {
            bleTransport.connect(endpoint).also {
                Log.d(TAG, "bleDiscoverAndConnect: dialed link to ${endpoint.opaqueAddress}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bleDiscoverAndConnect: connect to ${endpoint.opaqueAddress} failed: ${t::class.simpleName}: ${t.message}")
            throw t
        }
    }

    /**
     * Lowercase 56-char `.onion` for the edge that involves both
     * [ownPub] and [peerPub], regardless of direction, or null if
     * no such edge exists or the edge predates the Sprint 3 schema.
     */
    suspend fun peerOnionFor(ownPub: ByteArray, peerPub: ByteArray): String? {
        if (!database.isOpen) database.open()
        return database.trustEdgeDao.peerOnionForEndpoints(ownPub, peerPub)
    }

    /**
     * Every paired peer's `.onion` that we currently know about.
     * Returns the empty list before Sprint-3 edges land or before
     * the user has paired with anyone.
     */
    suspend fun knownPeerOnions(ownPub: ByteArray): List<String> {
        if (!database.isOpen) database.open()
        return database.trustEdgeDao.all()
            .asSequence()
            .filter { e -> e.fromPub.contentEquals(ownPub) || e.toPub.contentEquals(ownPub) }
            .mapNotNull { it.peerOnion }
            .distinct()
            .toList()
    }

    /**
     * Best-effort outbound dial to any paired peer over Tor. Returns
     * the first circuit that connects, or null if Tor isn't ready,
     * no `.onion` is known, or every dial fails. Each attempt runs
     * sequentially — kept simple in the first cut; the per-attempt
     * SOCKS5 dial timeout caps individual hangs at 30s.
     *
     * Throws only on [CancellationException] — callers race this
     * against the BLE paths and cancel the loser.
     */
    override suspend fun dialFirstKnownOnion(ownPub: ByteArray): Link? {
        if (torBackend.state.value !is TorBackend.State.Ready) return null
        val onions = knownPeerOnions(ownPub)
        if (onions.isEmpty()) return null
        for (onion in onions) {
            try {
                return torTransport.connect(
                    PeerEndpoint(Transport.Kind.TorHiddenService, onion),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.d(TAG, "dialFirstKnownOnion: $onion failed (${t.javaClass.simpleName}: ${t.message})")
                // try the next address
            }
        }
        return null
    }

    private companion object {
        private const val TAG = "TransportSelector"
    }
}
