package com.wyspr.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.RevocationEntity

/**
 * Store-side surface for revocation sync.
 *
 * Each method is a pure suspend operation against the [WysprDatabase]
 * and optionally the in-memory [TrustGraph]. The sync round function
 * ([runRevocationSyncRound]) orchestrates the have/want/push exchange;
 * this repository provides the data operations.
 *
 * Wire-format reconstruction from the [RevocationEntity] is safe because
 * the CBOR encoding is canonical — the same field values always produce
 * the same bytes, which means the other side verifies the same signature
 * the issuer produced.
 */
class RevocationSyncRepository(
    private val communityId: ByteArray,
) {

    /**
     * All revocation (issuerPub, targetPub) pairs stored in the database.
     * Used to build the HaveSet message.
     */
    suspend fun haveSet(database: WysprDatabase): List<Pair<ByteArray, ByteArray>> =
        database.revocationDao.all().map { it.issuerPub to it.targetPub }

    /**
     * Returns the subset of [theirSet] that this device does NOT already
     * have. Content-based equality on the (issuer, target) byte arrays.
     */
    suspend fun want(
        database: WysprDatabase,
        theirSet: List<Pair<ByteArray, ByteArray>>,
    ): List<Pair<ByteArray, ByteArray>> {
        val ours = haveSet(database).mapTo(HashSet()) { RevocationKey(it.first, it.second) }
        return theirSet.filter { (issuer, target) -> RevocationKey(issuer, target) !in ours }
    }

    /**
     * Reconstruct the full wire-encoded [RevocationCertificate] for each
     * requested (issuer, target) pair. Entries not found in the database
     * are silently dropped.
     */
    suspend fun fetch(
        database: WysprDatabase,
        keys: List<Pair<ByteArray, ByteArray>>,
    ): List<ByteArray> {
        val indexed = database.revocationDao.all().associateBy {
            RevocationKey(it.issuerPub, it.targetPub)
        }
        return keys.mapNotNull { (issuer, target) ->
            val entity = indexed[RevocationKey(issuer, target)] ?: return@mapNotNull null
            rebuildWireBytes(entity)  // null for legacy rows w/o stored community
        }
    }

    /**
     * Decode, verify, and persist one revocation certificate received
     * from a peer.
     *
     * Steps:
     * 1. Decode the wire bytes into a [RevocationCertificate].
     * 2. Verify the Ed25519 signature with [sodium].
     * 3. If [trustGraph] is provided, check that the issuer is trusted
     *    (not Unknown or Quarantined). Reject if untrusted.
     * 4. Persist to the database.
     * 5. If [trustGraph] is provided, call [TrustGraph.ingestRevocation]
     *    to update the in-memory graph.
     *
     * Returns true iff the certificate was accepted.
     */
    suspend fun ingest(
        database: WysprDatabase,
        wireCert: ByteArray,
        trustGraph: TrustGraph?,
        sodium: LazySodiumAndroid,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean = runCatching {
        val cert = RevocationCertificate.fromWire(wireCert)

        // CRITICAL: reject revocations signed against a different
        // community. Without this guard, a trusted member of community
        // A can sign a revocation referencing community B; the sig
        // verifies, our trust graph accepts the issuer, and the cert
        // would quarantine the target in B — an unrecoverable cross-
        // community attack.
        if (!cert.communityId.bytes.contentEquals(communityId)) return false

        // Verify the Ed25519 signature AND time bounds. Without
        // bounds, a trusted-then-compromised peer could forge
        // `issuedAt = Long.MAX_VALUE` revocations that always pass
        // every other check.
        if (!cert.verify(sodium, nowSeconds)) return false

        if (trustGraph != null) {
            val issuerLevel = trustGraph.trustLevel(cert.issuerPub)
            if (issuerLevel != TrustLevel.Full && issuerLevel != TrustLevel.Root) {
                return false
            }
            val targetLevel = trustGraph.trustLevel(cert.targetPub)
            if (targetLevel == TrustLevel.Root && issuerLevel != TrustLevel.Root) {
                return false
            }
        }

        // Persist to database — including the originally-signed
        // communityId so re-broadcast bytes match what the issuer
        // produced.
        database.revocationDao.upsert(
            RevocationEntity(
                issuerPub = cert.issuerPub.bytes,
                targetPub = cert.targetPub.bytes,
                issuedAt = cert.issuedAt,
                reasonCode = cert.reasonCode.name,
                signature = cert.signature,
                communityId = cert.communityId.bytes,
            )
        )

        // Update in-memory graph if provided.
        trustGraph?.ingestRevocation(cert)
        true
    }.getOrDefault(false)

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * Reconstruct the canonical CBOR wire bytes of a [RevocationCertificate]
     * from the fields stored in [RevocationEntity].
     *
     * The encoding must match [RevocationCertificate.wireBytes] exactly:
     *
     *     [version(1), issuerPub, targetPub, communityId, issuedAt, reasonCode.tag, signature]
     */
    private fun rebuildWireBytes(entity: RevocationEntity): ByteArray? {
        val reasonCode = RevocationCertificate.ReasonCode.valueOf(entity.reasonCode)
        // Legacy rows (pre-v12) may have an empty community blob —
        // refuse to re-broadcast them rather than fabricate bytes the
        // issuer never signed. They'll be re-synced from a peer that
        // has the originally-signed form.
        if (entity.communityId.isEmpty()) return null
        return Cbor.encode {
            arrayHeader(7)
            uint(RevocationCertificate.VERSION.toLong())
            bytes(entity.issuerPub)
            bytes(entity.targetPub)
            bytes(entity.communityId)
            uint(entity.issuedAt)
            uint(reasonCode.tag.toLong())
            bytes(entity.signature)
        }
    }

    /**
     * Content-equality wrapper for [Pair]<[ByteArray], [ByteArray]>.
     */
    private data class RevocationKey(
        val issuer: ByteArray,
        val target: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RevocationKey) return false
            return issuer.contentEquals(other.issuer) && target.contentEquals(other.target)
        }
        override fun hashCode(): Int {
            var r = issuer.contentHashCode()
            r = 31 * r + target.contentHashCode()
            return r
        }
    }
}
