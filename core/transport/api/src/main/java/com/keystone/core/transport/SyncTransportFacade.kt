package com.keystone.core.transport

import com.keystone.core.identity.CommunityId
import kotlinx.coroutines.flow.Flow

/**
 * Composed view of every transport the sync engine is allowed to use.
 *
 * The handshake-time pairing flow still goes over BLE only (see
 * `feature:onboarding/OnboardingViewModel`) — Tor isn't usable before
 * the QR exchange has produced a `peerOnion`. Everything past pairing
 * (messaging sync, future marketplace sync, coordination sync) wants
 * the choice: BLE if both peers are in the same room, Tor if either is
 * on the other side of the network.
 *
 * Implementations:
 *   - `app/transport/TransportSelector` is the production implementation
 *     that owns the BleTransport + TorHiddenServiceTransport singletons
 *     and the trust-edge database.
 *
 * Lifecycle is paired with a single sync round: caller invokes
 * [startAll], runs one race of `(bleDiscoverAndConnect, dialFirstKnownOnion,
 * acceptedLinks.first())`, then [stopAll] to release radios and
 * Tor-side resources.
 */
interface SyncTransportFacade {

    /** Start every transport for [communityId] (advertise/scan, Tor listener bind). */
    suspend fun startAll(communityId: CommunityId)

    /** Stop every transport. Idempotent. */
    suspend fun stopAll()

    /**
     * Inbound links from any transport, merged into a single flow. A
     * responder-side caller takes `first()` here and treats whichever
     * Link arrives first as the active session.
     */
    fun acceptedLinks(): Flow<Link>

    /**
     * Close + discard any inbound Links currently buffered in the
     * accept channels. Called by the responder side immediately
     * before subscribing to [acceptedLinks] in a new sync round.
     *
     * The Tor accept channel persists across rounds (see the
     * [TransportSelector.stopAll] kdoc) which means it can hold
     * stale inbounds: connections that completed in a previous
     * round, or were queued by the Tor daemon while our listener
     * was bound but no collector was actively reading. If a fresh
     * round picks up a stale Link, the peer that produced that
     * Link is long gone and the Noise XX read on the responder
     * side hangs forever.
     */
    fun drainStaleAccepted()

    /**
     * Suspend until a BLE peer is discovered AND a Link to it is
     * established. Honours coroutine cancellation — the loser of a
     * race against [dialFirstKnownOnion] or [acceptedLinks] is
     * cancelled cleanly without leaving a half-open GATT client.
     */
    suspend fun bleDiscoverAndConnect(): Link

    /**
     * Try to dial any paired peer's `.onion` over Tor and return the
     * first circuit that connects. Returns null if Tor isn't bootstrapped
     * yet, no edge involving [ownPub] carries a peerOnion, or every dial
     * attempt failed. The caller treats null as "Tor isn't usable right
     * now, fall back to BLE".
     */
    suspend fun dialFirstKnownOnion(ownPub: ByteArray): Link?
}
