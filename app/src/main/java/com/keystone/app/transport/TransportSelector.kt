package com.keystone.app.transport

import android.util.Log
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.identity.CommunityId
import com.keystone.core.transport.Link
import com.keystone.core.transport.PeerEndpoint
import com.keystone.core.transport.SyncTransportFacade
import com.keystone.core.transport.TorBackend
import com.keystone.core.transport.Transport
import com.keystone.core.transport.bluetooth.BleTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge

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
    private val database: KeystoneDatabase,
) : SyncTransportFacade {

    override suspend fun startAll(communityId: CommunityId) {
        torTransport.start(communityId)
        bleTransport.start(communityId)
    }

    override suspend fun stopAll() {
        runCatching { torTransport.stop() }
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

    /**
     * Wait for a BLE peer to advertise, then connect to it. The two
     * BleTransport calls are kept as one method on the facade because
     * the result is opaque to the caller — either we have a Link or
     * we don't.
     */
    override suspend fun bleDiscoverAndConnect(): Link {
        val endpoint = bleTransport.discovered().first()
        return bleTransport.connect(endpoint)
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
                Log.d(TAG, "dialFirstKnownOnion: $onion failed (${t.javaClass.simpleName})")
                // try the next address
            }
        }
        return null
    }

    private companion object {
        private const val TAG = "TransportSelector"
    }
}
