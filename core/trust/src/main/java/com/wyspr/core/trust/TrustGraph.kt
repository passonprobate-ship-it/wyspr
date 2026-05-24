package com.wyspr.core.trust

import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey

/**
 * The local view of the Web of Trust.
 *
 * Each node holds its own (partial) graph. There is no global consensus —
 * graphs converge as peers sync, and partitioning is a feature, not a bug.
 * SECURITY-MODEL.md §3.7.
 */
interface TrustGraph {

    val communityId: CommunityId

    /** Insert a new edge from a verified handshake or sync. */
    fun addEdge(edge: TrustEdge)

    /** Remove an edge — used only when both endpoints request it. */
    fun removeEdge(from: PublicKey, to: PublicKey)

    /** Ingest a revocation; moves the target to Quarantined locally. */
    fun ingestRevocation(cert: RevocationCertificate)

    /** Ingest a key rotation; rekeys edges and retires the old key. */
    fun ingestKeyRotation(cert: KeyRotationCertificate)

    /** Compute the current trust level for a peer. */
    fun trustLevel(peer: PublicKey): TrustLevel

    /** Check if this device can issue invitations right now. */
    fun canIssueInvitations(self: PublicKey): Boolean

    /** All edges as a snapshot — for sync HaveSet computation. */
    fun snapshot(): TrustGraphSnapshot

    /** Tuning constants — community-scoped. SECURITY-MODEL.md §3.4. */
    data class Parameters(
        val requiredIndependentPathsForFull: Int = 2,
        val maxPathLength: Int = 4,
    )
}

data class TrustEdge(
    val from: PublicKey,
    val to: PublicKey,
    val vouchLevel: InvitationCertificate.VouchLevel,
    val establishedAt: Long,
    val certBlob: ByteArray,
    val certSigner: PublicKey,
    /**
     * Peer's HSv3 hidden-service address, captured at handshake time
     * from their [HandshakeQr.onionAddress]. The "peer" here is the
     * not-self endpoint of this edge — Sprint 4's
     * `TorHiddenServiceTransport` reads this field to know where to
     * dial when BLE is unavailable.
     *
     * Null when the peer hadn't bootstrapped Tor at pairing time. A
     * future "address rotation" envelope (PROTOCOLS.md §3.5, deferred
     * to v0.6.4) will let peers update this without re-pairing.
     */
    val peerOnion: String? = null,
)

data class TrustGraphSnapshot(
    val edges: List<TrustEdge>,
    val revocations: List<RevocationCertificate>,
)
