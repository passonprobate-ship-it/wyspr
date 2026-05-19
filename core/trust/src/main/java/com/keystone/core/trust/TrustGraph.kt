package com.keystone.core.trust

import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey

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
)

data class TrustGraphSnapshot(
    val edges: List<TrustEdge>,
    val revocations: List<RevocationCertificate>,
)
