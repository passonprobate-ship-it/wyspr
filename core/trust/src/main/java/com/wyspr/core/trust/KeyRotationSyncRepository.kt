package com.wyspr.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.KeyRotationEntity

class KeyRotationSyncRepository(
    private val communityId: ByteArray,
) {

    suspend fun haveSet(database: WysprDatabase): List<Pair<ByteArray, ByteArray>> =
        database.keyRotationDao.all().map { it.oldPub to it.newPub }

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
    ): Boolean = runCatching {
        val cert = KeyRotationCertificate.fromWire(wireCert)

        if (!cert.communityId.bytes.contentEquals(communityId)) return false

        if (!cert.verify(sodium, nowSeconds)) return false

        if (trustGraph != null) {
            val oldLevel = trustGraph.trustLevel(cert.oldPub)
            if (oldLevel == TrustLevel.Quarantined || oldLevel == TrustLevel.Unknown) {
                return false
            }
        }

        // Reject if we already have a rotation from this oldPub to a
        // DIFFERENT newPub — the old key can only rotate once.
        val existing = database.keyRotationDao.byOldPub(cert.oldPub.bytes)
        if (existing.any { !it.newPub.contentEquals(cert.newPub.bytes) }) {
            return false
        }

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

        applyRotation(database, cert, onionString)

        trustGraph?.ingestKeyRotation(cert)

        true
    }.getOrDefault(false)

    private suspend fun applyRotation(
        database: WysprDatabase,
        cert: KeyRotationCertificate,
        newOnion: String?,
    ) {
        val oldBytes = cert.oldPub.bytes
        val newBytes = cert.newPub.bytes

        // Rekey trust edges
        val edges = database.trustEdgeDao.all()
        for (edge in edges) {
            if (edge.fromPub.contentEquals(oldBytes)) {
                database.trustEdgeDao.delete(edge.fromPub, edge.toPub)
                database.trustEdgeDao.upsert(
                    edge.copy(
                        fromPub = newBytes,
                        peerOnion = newOnion ?: edge.peerOnion,
                    )
                )
            }
            if (edge.toPub.contentEquals(oldBytes)) {
                database.trustEdgeDao.delete(edge.fromPub, edge.toPub)
                database.trustEdgeDao.upsert(
                    edge.copy(
                        toPub = newBytes,
                        peerOnion = newOnion ?: edge.peerOnion,
                    )
                )
            }
        }

        // Rekey contact record
        val contact = database.contactDao.byPub(oldBytes)
        if (contact != null) {
            database.contactDao.clear(oldBytes)
            database.contactDao.upsert(contact.copy(peerPub = newBytes))
        }

        // Rekey message threads
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
}
