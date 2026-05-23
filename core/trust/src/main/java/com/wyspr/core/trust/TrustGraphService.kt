package com.wyspr.core.trust

import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.RevocationEntity
import com.wyspr.core.database.entities.TrustEdgeEntity
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-level orchestrator that hydrates a [TrustGraph] from the
 * encrypted database and answers authorization questions before the
 * handshake mints any persistent state.
 *
 * Today's caller is [HandshakeProtocolImpl], which consults
 * [canIssueInvitations] at the top of `RealSession.run()` and aborts
 * with [HandshakeProtocol.AbortReason.NotAuthorized] if the local
 * device is not allowed to mint a fresh `InvitationCertificate`. The
 * rule for v0.2:
 *
 *   - Founders (the device that called `CommunityService.foundNewCommunity`)
 *     are Roots and may always issue.
 *   - Non-founders need ≥ K vertex-disjoint paths from a Root over
 *     `FULL` edges — exactly the quorum [TrustGraphImpl] computes.
 *     Today v0.1 only writes `PROVISIONAL` edges and there is only
 *     one Root per community, so non-founders cannot issue until a
 *     future sprint adds Provisional→Full promotion via co-signed
 *     attestations.
 *
 * The graph is rebuilt on demand from the DB; rebuilding is cheap
 * (a few hundred rows max for any realistic community) and avoids
 * a separate cache-invalidation story when peers mutate the
 * `trust_edge` table out-of-band via the sync engine. If the cost
 * ever shows up in profiling, swap to a [Mutex]-guarded cache here
 * — the public API does not change.
 */
class TrustGraphService(
    private val database: WysprDatabase,
    private val keystore: KeystoreManager,
) {

    private val lock = Mutex()

    /**
     * True iff [self] is the local device's identity AND the local
     * device's role in the active community permits issuing fresh
     * trust certificates. Returns false in any of:
     *   - no active community membership
     *   - self ≠ local identity
     *   - device is non-founder and graph quorum is unmet
     */
    suspend fun canIssueInvitations(self: PublicKey): Boolean = lock.withLock {
        val ownPub = runCatching {
            PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
        }.getOrNull() ?: return@withLock false
        // Defence-in-depth: refuse unless the caller's claimed self
        // matches the local identity. The handshake passes its own
        // local pub here, so any divergence indicates a logic bug
        // upstream.
        if (!self.bytes.contentEquals(ownPub.bytes)) return@withLock false

        val graph = buildGraph(ownPub) ?: return@withLock false
        graph.canIssueInvitations(self)
    }

    /**
     * Returns the current local view of the graph for a caller that
     * wants to do its own queries (e.g. a future "trust level"
     * indicator on a peer's profile screen). Returns null if there
     * is no active community on this device.
     */
    suspend fun snapshot(): TrustGraph? = lock.withLock {
        val ownPub = runCatching {
            PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
        }.getOrNull() ?: return@withLock null
        buildGraph(ownPub)
    }

    private suspend fun buildGraph(ownPub: PublicKey): TrustGraph? {
        if (!database.isOpen) database.open()
        val membership = database.communityMembershipDao.firstOrNull()
            ?: return null
        val communityId = CommunityId(membership.communityId)
        // Founder = Root. Until a future sprint introduces a
        // multi-Root mechanism (co-founder ceremony, recovery
        // protocol), this is the only Root in the local graph.
        val roots: Set<PublicKey> = if (membership.isFounder) setOf(ownPub) else emptySet()
        val edges = database.trustEdgeDao.all().map { it.toTrustEdge() }
        // Persisted revocations are trusted: they were verified by
        // RevocationSyncRepository.ingest before they reached the DB.
        // Replaying them here surfaces the matching peers as
        // Quarantined in [trustLevel], which is what gates further
        // revocations issued by an already-revoked attacker from being
        // accepted in this round.
        val revocations = database.revocationDao.all().map { it.toRevocation(communityId) }
        return TrustGraphImpl(
            communityId = communityId,
            roots = roots,
            initialEdges = edges,
            initialRevocations = revocations,
        )
    }

    private fun RevocationEntity.toRevocation(communityId: CommunityId): RevocationCertificate =
        RevocationCertificate(
            version = RevocationCertificate.VERSION,
            issuerPub = PublicKey(issuerPub),
            targetPub = PublicKey(targetPub),
            communityId = communityId,
            issuedAt = issuedAt,
            reasonCode = RevocationCertificate.ReasonCode.valueOf(reasonCode),
            signature = signature,
        )

    private fun TrustEdgeEntity.toTrustEdge(): TrustEdge = TrustEdge(
        from = PublicKey(fromPub),
        to = PublicKey(toPub),
        vouchLevel = runCatching {
            InvitationCertificate.VouchLevel.valueOf(vouchLevel)
        }.getOrDefault(InvitationCertificate.VouchLevel.PROVISIONAL),
        establishedAt = establishedAt,
        certBlob = certBlob,
        certSigner = PublicKey(certSigner),
    )
}
