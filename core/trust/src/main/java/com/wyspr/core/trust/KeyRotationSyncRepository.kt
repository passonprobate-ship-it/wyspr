package com.wyspr.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.KeyRotationEntity
import com.wyspr.core.identity.PublicKey

class KeyRotationSyncRepository(
    private val communityId: ByteArray,
) {

    suspend fun haveSet(database: WysprDatabase): List<Pair<ByteArray, ByteArray>> =
        database.keyRotationDao.all()
            .filter { it.communityId.contentEquals(communityId) }
            .map { it.oldPub to it.newPub }

    suspend fun want(
        database: WysprDatabase,
        theirSet: List<Pair<ByteArray, ByteArray>>,
    ): List<Pair<ByteArray, ByteArray>> {
        val ours = haveSet(database).mapTo(HashSet()) { RotationKey(it.first, it.second) }
        return theirSet.filter { (old, new) -> RotationKey(old, new) !in ours }
    }

    suspend fun fetch(
        database: WysprDatabase,
        keys: List<Pair<ByteArray, ByteArray>>,
    ): List<ByteArray> {
        val indexed = database.keyRotationDao.all().associateBy {
            RotationKey(it.oldPub, it.newPub)
        }
        return keys.mapNotNull { (old, new) ->
            val entity = indexed[RotationKey(old, new)] ?: return@mapNotNull null
            rebuildWireBytes(entity)
        }
    }

    suspend fun ingest(
        database: WysprDatabase,
        wireCert: ByteArray,
        trustGraph: TrustGraph?,
        sodium: LazySodiumAndroid,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        val cert = storeIfValid(database, wireCert, sodium, nowSeconds) ?: return false
        return applyIfTrusted(database, cert, trustGraph)
    }

    suspend fun resolveAndIngestBatch(
        database: WysprDatabase,
        wireCerts: List<ByteArray>,
        trustGraph: TrustGraph?,
        sodium: LazySodiumAndroid,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Int {
        val stored = wireCerts.mapNotNull { wire ->
            storeIfValid(database, wire, sodium, nowSeconds)
        }
        if (stored.isEmpty()) return 0

        val applied = mutableSetOf<RotationKey>()
        var accepted = 0

        var progress = true
        while (progress) {
            progress = false
            for (cert in stored) {
                val key = RotationKey(cert.oldPub.bytes, cert.newPub.bytes)
                if (key in applied) continue
                if (applyIfTrusted(database, cert, trustGraph)) {
                    applied.add(key)
                    accepted++
                    progress = true
                }
            }
        }

        for (cert in stored) {
            val key = RotationKey(cert.oldPub.bytes, cert.newPub.bytes)
            if (key in applied) continue
            accepted += tryChainApply(database, cert, trustGraph)
            applied.add(key)
        }

        return accepted
    }

    private suspend fun storeIfValid(
        database: WysprDatabase,
        wireCert: ByteArray,
        sodium: LazySodiumAndroid,
        nowSeconds: Long,
    ): KeyRotationCertificate? = runCatching {
        val cert = KeyRotationCertificate.fromWire(wireCert)

        if (!cert.communityId.bytes.contentEquals(communityId)) return null
        if (!cert.verify(sodium, nowSeconds)) return null

        val existing = database.keyRotationDao.byOldPub(cert.oldPub.bytes)
        if (existing.any { !it.newPub.contentEquals(cert.newPub.bytes) }) return null

        val onionString = cert.newOnion?.let { String(it, Charsets.US_ASCII) }
        database.keyRotationDao.upsert(
            KeyRotationEntity(
                oldPub = cert.oldPub.bytes,
                newPub = cert.newPub.bytes,
                communityId = cert.communityId.bytes,
                issuedAt = cert.issuedAt,
                newOnion = onionString,
                signature = cert.signature,
            )
        )
        cert
    }.getOrNull()

    private suspend fun applyIfTrusted(
        database: WysprDatabase,
        cert: KeyRotationCertificate,
        trustGraph: TrustGraph?,
    ): Boolean {
        if (trustGraph != null) {
            val oldLevel = trustGraph.trustLevel(cert.oldPub)
            if (oldLevel == TrustLevel.Quarantined || oldLevel == TrustLevel.Unknown) {
                return false
            }
        }
        applyRotation(database, cert, cert.newOnion?.let { String(it, Charsets.US_ASCII) })
        trustGraph?.ingestKeyRotation(cert)
        return true
    }

    private suspend fun tryChainApply(
        database: WysprDatabase,
        cert: KeyRotationCertificate,
        trustGraph: TrustGraph?,
    ): Int {
        val chain = buildChainToTrusted(database, cert.oldPub.bytes, trustGraph)
            ?: return 0

        var accepted = 0
        for (entity in chain) {
            val chainCert = entityToCert(entity)
            if (applyIfTrusted(database, chainCert, trustGraph)) {
                accepted++
            }
        }
        if (applyIfTrusted(database, cert, trustGraph)) {
            accepted++
        }
        return accepted
    }

    private suspend fun buildChainToTrusted(
        database: WysprDatabase,
        unknownPub: ByteArray,
        trustGraph: TrustGraph?,
    ): List<KeyRotationEntity>? {
        val chain = ArrayDeque<KeyRotationEntity>()
        var currentPub = unknownPub
        val visited = mutableSetOf(RotationKey(unknownPub, ByteArray(0)))
        var depth = 0

        while (depth < MAX_CHAIN_DEPTH) {
            val predecessors = database.keyRotationDao.byNewPub(currentPub)
            if (predecessors.isEmpty()) return null

            val predecessor = predecessors.firstOrNull {
                it.communityId.contentEquals(communityId)
            } ?: return null

            val key = RotationKey(predecessor.oldPub, predecessor.newPub)
            if (!visited.add(key)) return null

            chain.addFirst(predecessor)

            if (trustGraph != null) {
                val level = trustGraph.trustLevel(PublicKey(predecessor.oldPub))
                if (level != TrustLevel.Quarantined && level != TrustLevel.Unknown) {
                    return chain.toList()
                }
            } else {
                return chain.toList()
            }

            currentPub = predecessor.oldPub
            depth++
        }
        return null
    }

    private fun entityToCert(entity: KeyRotationEntity): KeyRotationCertificate =
        KeyRotationCertificate(
            version = KeyRotationCertificate.VERSION,
            oldPub = PublicKey(entity.oldPub),
            newPub = PublicKey(entity.newPub),
            communityId = com.wyspr.core.identity.CommunityId(entity.communityId),
            issuedAt = entity.issuedAt,
            newOnion = entity.newOnion?.toByteArray(Charsets.US_ASCII),
            signature = entity.signature,
        )

    private suspend fun applyRotation(
        database: WysprDatabase,
        cert: KeyRotationCertificate,
        newOnion: String?,
    ) {
        val oldBytes = cert.oldPub.bytes
        val newBytes = cert.newPub.bytes

        val edges = database.trustEdgeDao.all()
        for (edge in edges) {
            val fromMatch = edge.fromPub.contentEquals(oldBytes)
            val toMatch = edge.toPub.contentEquals(oldBytes)
            val signerMatch = edge.certSigner?.let { it.contentEquals(oldBytes) } == true
            if (!fromMatch && !toMatch && !signerMatch) continue

            database.trustEdgeDao.delete(edge.fromPub, edge.toPub)
            database.trustEdgeDao.upsert(
                edge.copy(
                    fromPub = if (fromMatch) newBytes else edge.fromPub,
                    toPub = if (toMatch) newBytes else edge.toPub,
                    certSigner = if (signerMatch) newBytes else edge.certSigner,
                    peerOnion = if (fromMatch || toMatch) newOnion ?: edge.peerOnion else edge.peerOnion,
                )
            )
        }

        val contact = database.contactDao.byPub(oldBytes)
        if (contact != null) {
            database.contactDao.clear(oldBytes)
            database.contactDao.upsert(contact.copy(peerPub = newBytes))
        }

        database.messageDao.rekeyThread(oldBytes, newBytes)
        database.messageDao.rekeyFromPub(oldBytes, newBytes)
        database.messageDao.rekeyToPub(oldBytes, newBytes)
    }

    private fun rebuildWireBytes(entity: KeyRotationEntity): ByteArray {
        val onionBytes = entity.newOnion?.toByteArray(Charsets.US_ASCII)
        return Cbor.encode {
            arrayHeader(7)
            uint(KeyRotationCertificate.VERSION.toLong())
            bytes(entity.oldPub)
            bytes(entity.newPub)
            bytes(entity.communityId)
            uint(entity.issuedAt)
            if (onionBytes != null) bytes(onionBytes) else nullValue()
            bytes(entity.signature)
        }
    }

    private data class RotationKey(
        val oldPub: ByteArray,
        val newPub: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RotationKey) return false
            return oldPub.contentEquals(other.oldPub) && newPub.contentEquals(other.newPub)
        }
        override fun hashCode(): Int {
            var r = oldPub.contentHashCode()
            r = 31 * r + newPub.contentHashCode()
            return r
        }
    }

    private companion object {
        const val MAX_CHAIN_DEPTH = 10
    }
}
