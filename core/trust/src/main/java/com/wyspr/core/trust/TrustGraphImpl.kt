package com.wyspr.core.trust

import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory implementation of [TrustGraph] with the K-independent-paths
 * quorum from SECURITY-MODEL.md §3.4.
 *
 * Trust levels are computed lazily on every query. For the scale of a
 * community-of-friends graph (≤ ~10k nodes, ≤ ~50k edges) the BFS cost
 * is negligible — caching would invite invalidation bugs that outweigh
 * the tens of microseconds we'd save.
 *
 * ## Internal keying
 *
 * `PublicKey` is a `@JvmInline value class` wrapping a `ByteArray`,
 * which gives reference equality on its underlying array. That's
 * useless for Map/Set membership, so this class re-keys everything
 * internally on the wrapped key bytes via [ByteBuffer.wrap], which is
 * content-equal under the JDK contract. The public API still takes
 * and returns plain `PublicKey`.
 *
 * ## K-paths quorum
 *
 * A peer P is [TrustLevel.Full] iff there exist at least
 * [TrustGraph.Parameters.requiredIndependentPathsForFull]
 * *vertex-disjoint* paths from some Root to P, each of length ≤
 * [TrustGraph.Parameters.maxPathLength] edges, using only edges with
 * [InvitationCertificate.VouchLevel.FULL]. Roots and the target
 * itself do not count as intermediates — a Root may legitimately
 * vouch on multiple disjoint chains. A single compromised
 * intermediate cannot single-handedly elevate a stranger to Full.
 *
 * Algorithm: repeated multi-source BFS. Each round finds a shortest
 * path from any unblocked Root to the target, then blocks every
 * non-endpoint vertex on that path before the next round. Stops when
 * no further path exists or K paths have been found.
 *
 * ## Provisional
 *
 * A peer is [TrustLevel.Provisional] iff at least one path exists
 * from a Root (FULL or PROVISIONAL edges, length ≤ maxPathLength)
 * but the Full quorum is not met.
 *
 * ## Quarantine
 *
 * [ingestRevocation] writes the target's pubkey into the revoked set.
 * From that moment, [trustLevel] returns [TrustLevel.Quarantined] for
 * the target regardless of any paths that exist. Revoked vertices
 * are also blocked during path search, so a quarantined member
 * cannot launder trust for anyone downstream.
 */
class TrustGraphImpl(
    override val communityId: CommunityId,
    roots: Set<PublicKey>,
    private val parameters: TrustGraph.Parameters = TrustGraph.Parameters(),
    initialEdges: Collection<TrustEdge> = emptyList(),
    initialRevocations: Collection<RevocationCertificate> = emptyList(),
) : TrustGraph {

    private val lock = ReentrantReadWriteLock()

    /** Root pubkey bytes, content-equality keyed. */
    private val rootKeys: Set<ByteBuffer> = roots.mapTo(HashSet()) { it.bytes.wrap() }

    /** outgoing[from-bytes] -> list of edges leaving `from`. */
    private val outgoing: MutableMap<ByteBuffer, MutableList<TrustEdge>> = HashMap()

    /** Dedupe of (from, to) pairs. */
    private val edgeSet: MutableSet<EdgeKey> = HashSet()

    private val revocations: MutableList<RevocationCertificate> = ArrayList()
    private val revoked: MutableSet<ByteBuffer> = HashSet()

    init {
        for (e in initialEdges) addEdgeInternal(e)
        for (r in initialRevocations) ingestRevocationInternal(r)
    }

    override fun addEdge(edge: TrustEdge) = lock.write { addEdgeInternal(edge) }

    override fun removeEdge(from: PublicKey, to: PublicKey): Unit = lock.write {
        val fromKey = from.bytes.wrap()
        val toKey = to.bytes.wrap()
        val list = outgoing[fromKey] ?: return@write
        list.removeAll { it.to.bytes.wrap() == toKey }
        edgeSet.remove(EdgeKey(fromKey, toKey))
        if (list.isEmpty()) outgoing.remove(fromKey)
    }

    override fun ingestRevocation(cert: RevocationCertificate): Unit =
        lock.write { ingestRevocationInternal(cert) }

    override fun trustLevel(peer: PublicKey): TrustLevel = lock.read {
        val peerKey = peer.bytes.wrap()
        if (peerKey in rootKeys) return@read TrustLevel.Root
        if (peerKey in revoked) return@read TrustLevel.Quarantined

        val k = parameters.requiredIndependentPathsForFull
        if (k > 0 && countIndependentPaths(peerKey, k, fullOnly = true) >= k) {
            return@read TrustLevel.Full
        }
        if (countIndependentPaths(peerKey, maxK = 1, fullOnly = false) >= 1) {
            return@read TrustLevel.Provisional
        }
        TrustLevel.Unknown
    }

    override fun canIssueInvitations(self: PublicKey): Boolean = lock.read {
        val key = self.bytes.wrap()
        if (key in revoked) return@read false
        if (key in rootKeys) return@read true
        trustLevel(self) == TrustLevel.Full
    }

    override fun snapshot(): TrustGraphSnapshot = lock.read {
        TrustGraphSnapshot(
            edges = outgoing.values.flatten(),
            revocations = revocations.toList(),
        )
    }

    // ── internals ─────────────────────────────────────────────────────

    private fun addEdgeInternal(edge: TrustEdge) {
        val fromKey = edge.from.bytes.wrap()
        val toKey = edge.to.bytes.wrap()
        if (!edgeSet.add(EdgeKey(fromKey, toKey))) return
        outgoing.getOrPut(fromKey) { ArrayList() }.add(edge)
    }

    private fun ingestRevocationInternal(cert: RevocationCertificate) {
        revocations.add(cert)
        revoked.add(cert.targetPub.bytes.wrap())
    }

    /**
     * Counts up to [maxK] vertex-disjoint paths from any Root to
     * [target], each at most [TrustGraph.Parameters.maxPathLength]
     * edges.
     *
     * Two things get blocked between rounds:
     *   1. Intermediates of the previous path become forbidden
     *      vertices — they can't appear on another path at all.
     *   2. Edges used on the previous path become forbidden edges —
     *      so a length-1 direct vouch can't be re-counted (it has
     *      no intermediates to block), and parallel longer paths
     *      can't reuse the same first hop from a shared root.
     *
     * Roots themselves stay reusable as path-starts: a single root
     * may legitimately anchor two disjoint chains via different
     * intermediates.
     */
    private fun countIndependentPaths(
        target: ByteBuffer,
        maxK: Int,
        fullOnly: Boolean,
    ): Int {
        if (target in rootKeys) return maxK
        val maxLen = parameters.maxPathLength
        if (maxLen <= 0) return 0
        val blockedVertices = HashSet<ByteBuffer>(revoked)
        val blockedEdges = HashSet<Pair<ByteBuffer, ByteBuffer>>()
        var found = 0
        while (found < maxK) {
            val path = shortestPath(target, maxLen, blockedVertices, blockedEdges, fullOnly)
                ?: break
            for (i in 1 until path.size - 1) blockedVertices.add(path[i])
            for (i in 0 until path.size - 1) blockedEdges.add(path[i] to path[i + 1])
            found++
        }
        return found
    }

    /**
     * Multi-source BFS from every Root toward [target]. Returns the
     * vertex sequence (source ... target) or null if no path of
     * length ≤ [maxLen] edges exists that avoids [blockedVertices]
     * and [blockedEdges].
     */
    private fun shortestPath(
        target: ByteBuffer,
        maxLen: Int,
        blockedVertices: Set<ByteBuffer>,
        blockedEdges: Set<Pair<ByteBuffer, ByteBuffer>>,
        fullOnly: Boolean,
    ): List<ByteBuffer>? {
        val parent = HashMap<ByteBuffer, ByteBuffer?>()
        val depth = HashMap<ByteBuffer, Int>()
        val frontier: ArrayDeque<ByteBuffer> = ArrayDeque()
        for (root in rootKeys) {
            if (root in blockedVertices) continue
            parent[root] = null
            depth[root] = 0
            frontier.addLast(root)
            if (root == target) return reconstruct(parent, target)
        }
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeFirst()
            if (cur == target) return reconstruct(parent, target)
            val d = depth[cur] ?: continue
            if (d >= maxLen) continue
            val neighbors = outgoing[cur] ?: continue
            for (edge in neighbors) {
                if (fullOnly && edge.vouchLevel != InvitationCertificate.VouchLevel.FULL) continue
                val next = edge.to.bytes.wrap()
                if ((cur to next) in blockedEdges) continue
                if (next in parent) continue  // BFS already found a shorter route.
                if (next != target && next in blockedVertices) continue
                parent[next] = cur
                depth[next] = d + 1
                frontier.addLast(next)
            }
        }
        return null
    }

    private fun reconstruct(
        parent: Map<ByteBuffer, ByteBuffer?>,
        target: ByteBuffer,
    ): List<ByteBuffer> {
        val out = ArrayDeque<ByteBuffer>()
        var cur: ByteBuffer? = target
        while (cur != null) {
            out.addFirst(cur)
            cur = parent[cur]
        }
        return out.toList()
    }

    private fun ByteArray.wrap(): ByteBuffer = ByteBuffer.wrap(this).asReadOnlyBuffer()

    private data class EdgeKey(val from: ByteBuffer, val to: ByteBuffer)
}
