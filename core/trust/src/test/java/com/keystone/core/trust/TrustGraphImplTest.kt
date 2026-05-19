package com.keystone.core.trust

import com.keystone.core.identity.CommunityId
import com.keystone.core.identity.PublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of [TrustGraphImpl] — the K-paths quorum is the
 * security-critical piece here; every test names the exact graph
 * shape and the trust level that must result.
 */
class TrustGraphImplTest {

    private val community = CommunityId(ByteArray(32) { it.toByte() })

    // Deterministic synthetic public keys — 32 bytes, distinguishable by first byte.
    private fun pk(tag: Byte) = PublicKey(ByteArray(32) { i -> if (i == 0) tag else 0 })
    private val root1 = pk(0x01)
    private val root2 = pk(0x02)
    private val alice = pk(0x10)
    private val bob = pk(0x11)
    private val carol = pk(0x12)
    private val dave = pk(0x13)
    private val eve = pk(0x14)
    private val stranger = pk(0x20)

    private fun edge(
        from: PublicKey,
        to: PublicKey,
        vouch: InvitationCertificate.VouchLevel = InvitationCertificate.VouchLevel.FULL,
    ) = TrustEdge(
        from = from,
        to = to,
        vouchLevel = vouch,
        establishedAt = 0L,
        certBlob = ByteArray(0),
        certSigner = from,
    )

    private fun graph(
        roots: Set<PublicKey> = setOf(root1),
        parameters: TrustGraph.Parameters = TrustGraph.Parameters(),
        edges: Collection<TrustEdge> = emptyList(),
        revocations: Collection<RevocationCertificate> = emptyList(),
    ) = TrustGraphImpl(community, roots, parameters, edges, revocations)

    private fun revocation(target: PublicKey) = RevocationCertificate(
        version = RevocationCertificate.VERSION,
        issuerPub = root1,
        targetPub = target,
        communityId = community,
        issuedAt = 0L,
        reasonCode = RevocationCertificate.ReasonCode.COMPROMISED,
        signature = ByteArray(64),
    )

    // ── trust levels ──────────────────────────────────────────────────

    @Test fun root_isRoot() {
        val g = graph()
        assertEquals(TrustLevel.Root, g.trustLevel(root1))
    }

    @Test fun unknownPeer_isUnknown() {
        val g = graph()
        assertEquals(TrustLevel.Unknown, g.trustLevel(stranger))
    }

    @Test fun directVouchFromRoot_isProvisional_underDefaultKEquals2() {
        // root1 -> alice (FULL). One path only, K=2 → Provisional, not Full.
        val g = graph(edges = listOf(edge(root1, alice)))
        assertEquals(TrustLevel.Provisional, g.trustLevel(alice))
    }

    @Test fun twoDisjointPathsToTarget_promoteToFull() {
        // root1 -> alice -> dave AND root1 -> bob -> dave
        // The two intermediates (alice, bob) are disjoint → 2 paths → Full.
        val g = graph(
            edges = listOf(
                edge(root1, alice),
                edge(root1, bob),
                edge(alice, dave),
                edge(bob, dave),
            ),
        )
        assertEquals(TrustLevel.Full, g.trustLevel(dave))
    }

    @Test fun twoPathsSharingIntermediate_isProvisional() {
        // root1 -> alice -> dave AND root1 -> alice -> eve -> dave.
        // Both routes go through `alice`; not vertex-disjoint → Provisional.
        val g = graph(
            edges = listOf(
                edge(root1, alice),
                edge(alice, dave),
                edge(alice, eve),
                edge(eve, dave),
            ),
        )
        assertEquals(TrustLevel.Provisional, g.trustLevel(dave))
    }

    @Test fun provisionalEdge_doesNotContributeToFullQuorum() {
        // root1 -[FULL]-> alice -[FULL]-> dave  AND
        // root1 -[FULL]-> bob   -[PROV]-> dave
        // FULL-only count = 1 (alice path). Full requires K=2 FULL paths.
        val g = graph(
            edges = listOf(
                edge(root1, alice),
                edge(alice, dave),
                edge(root1, bob),
                edge(bob, dave, InvitationCertificate.VouchLevel.PROVISIONAL),
            ),
        )
        assertEquals(TrustLevel.Provisional, g.trustLevel(dave))
    }

    @Test fun twoRootsEachWithOnePath_promoteToFull() {
        // Two independent roots both vouch directly. Roots may both
        // appear on disjoint chains; intermediates count is zero, so
        // K=2 disjoint single-edge paths exist.
        val g = graph(
            roots = setOf(root1, root2),
            edges = listOf(
                edge(root1, alice),
                edge(root2, alice),
            ),
        )
        assertEquals(TrustLevel.Full, g.trustLevel(alice))
    }

    @Test fun pathLongerThanMax_excludedFromQuorum() {
        // root1 -> a -> b -> c -> d -> stranger  (length 5, max=4).
        // Single edge from root1 -> alice -> bob -> carol -> dave -> stranger
        val parameters = TrustGraph.Parameters(
            requiredIndependentPathsForFull = 2,
            maxPathLength = 4,
        )
        val g = graph(
            parameters = parameters,
            edges = listOf(
                edge(root1, alice),
                edge(alice, bob),
                edge(bob, carol),
                edge(carol, dave),
                edge(dave, stranger),
            ),
        )
        // No reachable path within 4 hops → Unknown.
        assertEquals(TrustLevel.Unknown, g.trustLevel(stranger))
    }

    @Test fun pathAtExactlyMaxLength_isAccepted() {
        val parameters = TrustGraph.Parameters(
            requiredIndependentPathsForFull = 1,
            maxPathLength = 3,
        )
        // root1 -> alice -> bob -> dave  (3 edges = max).
        val g = graph(
            parameters = parameters,
            edges = listOf(
                edge(root1, alice),
                edge(alice, bob),
                edge(bob, dave),
            ),
        )
        // K=1; one path of length 3 exists, FULL-only, → Full.
        assertEquals(TrustLevel.Full, g.trustLevel(dave))
    }

    // ── revocation ────────────────────────────────────────────────────

    @Test fun revokedPeer_isQuarantined() {
        val g = graph(
            edges = listOf(edge(root1, alice)),
            revocations = listOf(revocation(alice)),
        )
        assertEquals(TrustLevel.Quarantined, g.trustLevel(alice))
    }

    @Test fun revokedIntermediate_cannotLaunderTrust() {
        // root1 -> alice -> dave. Revoke alice. dave should drop to
        // Unknown (the only path went through a quarantined node).
        val g = graph(
            edges = listOf(
                edge(root1, alice),
                edge(alice, dave),
            ),
            revocations = listOf(revocation(alice)),
        )
        assertEquals(TrustLevel.Unknown, g.trustLevel(dave))
    }

    @Test fun revocationDoesNotAffectIndependentPaths() {
        // root1 -> alice -> dave AND root1 -> bob -> dave.
        // Revoke alice. dave still has bob's path → Provisional
        // (one remaining FULL path, K=2 not met).
        val g = graph(
            edges = listOf(
                edge(root1, alice),
                edge(alice, dave),
                edge(root1, bob),
                edge(bob, dave),
            ),
            revocations = listOf(revocation(alice)),
        )
        assertEquals(TrustLevel.Provisional, g.trustLevel(dave))
    }

    // ── canIssueInvitations ───────────────────────────────────────────

    @Test fun rootCanIssue() {
        assertTrue(graph().canIssueInvitations(root1))
    }

    @Test fun fullNodeCanIssue() {
        val g = graph(
            roots = setOf(root1, root2),
            edges = listOf(edge(root1, alice), edge(root2, alice)),
        )
        assertEquals(TrustLevel.Full, g.trustLevel(alice))
        assertTrue(g.canIssueInvitations(alice))
    }

    @Test fun provisionalNodeCannotIssue() {
        val g = graph(edges = listOf(edge(root1, alice)))
        assertEquals(TrustLevel.Provisional, g.trustLevel(alice))
        assertFalse(g.canIssueInvitations(alice))
    }

    @Test fun revokedSelfCannotIssue() {
        val g = graph(
            edges = listOf(edge(root1, alice)),
            revocations = listOf(revocation(alice)),
        )
        assertFalse(g.canIssueInvitations(alice))
    }

    // ── mutation ──────────────────────────────────────────────────────

    @Test fun addEdge_dedupes() {
        val g = graph()
        g.addEdge(edge(root1, alice))
        g.addEdge(edge(root1, alice))
        assertEquals(1, g.snapshot().edges.size)
    }

    @Test fun removeEdge_drops() {
        val g = graph(edges = listOf(edge(root1, alice)))
        assertEquals(TrustLevel.Provisional, g.trustLevel(alice))
        g.removeEdge(root1, alice)
        assertEquals(TrustLevel.Unknown, g.trustLevel(alice))
        assertEquals(0, g.snapshot().edges.size)
    }

    @Test fun ingestRevocation_recordsInSnapshot() {
        val g = graph()
        val r = revocation(alice)
        g.ingestRevocation(r)
        assertEquals(1, g.snapshot().revocations.size)
        assertEquals(TrustLevel.Quarantined, g.trustLevel(alice))
    }

    @Test fun snapshotIsIndependentOfState() {
        val g = graph(edges = listOf(edge(root1, alice)))
        val snap = g.snapshot()
        g.removeEdge(root1, alice)
        // The snapshot was a copy — still has the edge.
        assertEquals(1, snap.edges.size)
    }
}
