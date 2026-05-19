package com.keystone.core.transport

import com.keystone.core.identity.CommunityId
import kotlinx.coroutines.flow.Flow

/**
 * The single abstraction every transport implements.
 *
 * A transport is responsible for ONE thing: moving opaque, length-prefixed
 * frames between two endpoints that have already authenticated each other
 * via Noise. Transports MUST NOT:
 *
 *   - Inspect frame payloads.
 *   - Surface unauthenticated peers as connected.
 *   - Cache or persist frames.
 *   - Carry any per-device identifier at the MAC / advertisement layer
 *     (the community service UUID is the only published identifier).
 *
 * PROTOCOLS.md §1, §6.
 */
interface Transport {

    val kind: Kind

    /** Start advertising + scanning for peers of [communityId]. */
    suspend fun start(communityId: CommunityId)

    /** Stop advertising. Idempotent. */
    suspend fun stop()

    /**
     * Stream of peers discovered on this transport. Discovery does NOT
     * imply authentication; the consumer must complete a Noise session
     * before treating the peer as anything other than a raw socket.
     */
    fun discovered(): Flow<PeerEndpoint>

    /**
     * Open a bidirectional bytes channel to [endpoint]. The returned
     * [Link] is the wire — Noise is wrapped around it externally.
     */
    suspend fun connect(endpoint: PeerEndpoint): Link

    /**
     * Inbound counterpart to [connect]: a flow of [Link]s the transport
     * has accepted from peers that called connect() against us. The
     * Inviter (initiator) calls [connect]; the Invitee (responder)
     * collects [acceptedLinks]. Each Link is one accepted connection;
     * the caller is responsible for closing it.
     *
     * The flow is conflated by behavior — late subscribers do NOT
     * receive past connections. Subscribe before the peer connects.
     */
    fun acceptedLinks(): Flow<Link> = kotlinx.coroutines.flow.emptyFlow()

    enum class Kind {
        BluetoothLe,
        WifiDirect,
        TorHiddenService,
        /**
         * Reticulum / LoRa packet network — backbone-style mesh
         * carried by an ironmesh node or similar. Frames flow through
         * Reticulum's "Resource" abstraction which handles fragmentation
         * below our 16KB Link frame layer.
         */
        Reticulum,
    }
}

/**
 * An opaque endpoint that a transport knows how to reach. The contents
 * are transport-specific (BLE address, WiFi p2p device, .onion) and
 * carry no Keystone-level identity.
 */
data class PeerEndpoint(
    val kind: Transport.Kind,
    val opaqueAddress: String,
)

/**
 * A live duplex channel. Caller wraps it in [com.keystone.core.crypto.NoiseSession]
 * before exchanging anything meaningful. Frames are length-prefixed:
 *
 *     [ frame_len: u16 ][ payload: bytes ]   (max 16384)
 */
interface Link {
    val endpoint: PeerEndpoint

    /** Sends one frame. Blocks until on the wire (or queued by L2). */
    suspend fun send(frame: ByteArray)

    /** Receives frames as they arrive. Cancellable. */
    fun incoming(): Flow<ByteArray>

    /** Tear down. Idempotent. */
    suspend fun close()
}
