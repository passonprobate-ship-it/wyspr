package com.keystone.core.database

import com.keystone.core.database.dao.AccountDao
import com.keystone.core.database.dao.CommunityMembershipDao
import com.keystone.core.database.dao.CurrencyEnvelopeDao
import com.keystone.core.database.dao.MessageDao
import com.keystone.core.database.dao.RevocationDao
import com.keystone.core.database.dao.TrustEdgeDao
import com.keystone.core.database.entities.AccountEntity
import com.keystone.core.database.entities.CommunityMembershipEntity
import com.keystone.core.database.entities.CurrencyEnvelopeEntity
import com.keystone.core.database.entities.MessageEntity
import com.keystone.core.database.entities.RevocationEntity
import com.keystone.core.database.entities.TrustEdgeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// ── TrustEdgeDao ──────────────────────────────────────────────────────

class FakeTrustEdgeDao : TrustEdgeDao {

    private data class EdgeKey(val from: ByteArray, val to: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EdgeKey) return false
            return from.contentEquals(other.from) && to.contentEquals(other.to)
        }
        override fun hashCode(): Int {
            var r = from.contentHashCode()
            r = 31 * r + to.contentHashCode()
            return r
        }
    }

    private val store = LinkedHashMap<EdgeKey, TrustEdgeEntity>()

    override suspend fun upsert(edge: TrustEdgeEntity) {
        store[EdgeKey(edge.fromPub, edge.toPub)] = edge
    }

    override suspend fun all(): List<TrustEdgeEntity> = store.values.toList()

    override suspend fun delete(from: ByteArray, to: ByteArray) {
        store.remove(EdgeKey(from, to))
    }

    override suspend fun byToPub(toPub: ByteArray): TrustEdgeEntity? =
        store.values.firstOrNull { it.toPub.contentEquals(toPub) }

    override suspend fun peerOnionForEndpoints(a: ByteArray, b: ByteArray): String? =
        store.values.firstOrNull { edge ->
            (edge.fromPub.contentEquals(a) && edge.toPub.contentEquals(b)) ||
                (edge.fromPub.contentEquals(b) && edge.toPub.contentEquals(a))
        }?.peerOnion

    override suspend fun count(): Int = store.size

    fun clear() { store.clear() }
}

// ── RevocationDao ─────────────────────────────────────────────────────

class FakeRevocationDao : RevocationDao {

    private data class RevKey(val issuer: ByteArray, val target: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RevKey) return false
            return issuer.contentEquals(other.issuer) && target.contentEquals(other.target)
        }
        override fun hashCode(): Int {
            var r = issuer.contentHashCode()
            r = 31 * r + target.contentHashCode()
            return r
        }
    }

    private val store = LinkedHashMap<RevKey, RevocationEntity>()

    override suspend fun upsert(revocation: RevocationEntity) {
        store[RevKey(revocation.issuerPub, revocation.targetPub)] = revocation
    }

    override suspend fun all(): List<RevocationEntity> = store.values.toList()

    override suspend fun byTarget(target: ByteArray): List<RevocationEntity> =
        store.values.filter { it.targetPub.contentEquals(target) }

    fun clear() { store.clear() }
}

// ── AccountDao ────────────────────────────────────────────────────────

class FakeAccountDao : AccountDao {

    private data class PubKey(val pub: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PubKey) return false
            return pub.contentEquals(other.pub)
        }
        override fun hashCode(): Int = pub.contentHashCode()
    }

    private val store = LinkedHashMap<PubKey, AccountEntity>()

    override suspend fun upsert(account: AccountEntity) {
        store[PubKey(account.pub)] = account
    }

    override suspend fun insertNew(account: AccountEntity) {
        val key = PubKey(account.pub)
        check(store.put(key, account) == null) { "Account already exists: PK collision" }
    }

    override suspend fun update(account: AccountEntity) {
        store[PubKey(account.pub)] = account
    }

    override suspend fun get(pub: ByteArray): AccountEntity? = store[PubKey(pub)]

    override suspend fun all(): List<AccountEntity> = store.values.toList()

    override suspend fun count(): Int = store.size

    override suspend fun markSlashed(pub: ByteArray) {
        val key = PubKey(pub)
        store.computeIfPresent(key) { _, v -> v.copy(slashed = true, balanceCached = 0) }
    }

    override suspend fun deleteAll() { store.clear() }

    fun clear() { store.clear() }
}

// ── CurrencyEnvelopeDao ───────────────────────────────────────────────

class FakeCurrencyEnvelopeDao : CurrencyEnvelopeDao {

    private data class EnvKey(
        val community: ByteArray,
        val typeTag: Int,
        val primaryKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EnvKey) return false
            return typeTag == other.typeTag &&
                community.contentEquals(other.community) &&
                primaryKey.contentEquals(other.primaryKey)
        }
        override fun hashCode(): Int {
            var r = community.contentHashCode()
            r = 31 * r + typeTag
            r = 31 * r + primaryKey.contentHashCode()
            return r
        }
    }

    private val store = LinkedHashMap<EnvKey, CurrencyEnvelopeEntity>()

    override suspend fun insert(envelope: CurrencyEnvelopeEntity) {
        val key = EnvKey(envelope.community, envelope.typeTag, envelope.primaryKey)
        check(store.put(key, envelope) == null) { "CurrencyEnvelope PK collision: possible double-spend" }
    }

    override suspend fun replaceLocal(envelope: CurrencyEnvelopeEntity) {
        store[EnvKey(envelope.community, envelope.typeTag, envelope.primaryKey)] = envelope
    }

    override suspend fun get(community: ByteArray, typeTag: Int, primaryKey: ByteArray): CurrencyEnvelopeEntity? =
        store[EnvKey(community, typeTag, primaryKey)]

    override suspend fun byType(community: ByteArray, typeTag: Int): List<CurrencyEnvelopeEntity> =
        store.values.filter { it.community.contentEquals(community) && it.typeTag == typeTag }

    override suspend fun byTypeOrdered(community: ByteArray, typeTag: Int): List<CurrencyEnvelopeEntity> =
        store.values
            .filter { it.community.contentEquals(community) && it.typeTag == typeTag }
            .sortedBy { it.observedAt }

    override suspend fun forCommunity(community: ByteArray): List<CurrencyEnvelopeEntity> =
        store.values.filter { it.community.contentEquals(community) }

    override suspend fun countForCommunity(community: ByteArray): Int =
        store.values.count { it.community.contentEquals(community) }

    override suspend fun count(): Int = store.size

    override suspend fun deleteAll() { store.clear() }

    fun clear() { store.clear() }
}

// ── CommunityMembershipDao ────────────────────────────────────────────

class FakeCommunityMembershipDao : CommunityMembershipDao {

    private data class CidKey(val communityId: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CidKey) return false
            return communityId.contentEquals(other.communityId)
        }
        override fun hashCode(): Int = communityId.contentHashCode()
    }

    private val store = LinkedHashMap<CidKey, CommunityMembershipEntity>()

    override suspend fun upsert(row: CommunityMembershipEntity) {
        store[CidKey(row.communityId)] = row
    }

    override suspend fun firstOrNull(): CommunityMembershipEntity? =
        store.values.firstOrNull()

    override suspend fun all(): List<CommunityMembershipEntity> =
        store.values.toList()

    override suspend fun count(): Int = store.size

    override suspend fun deleteAll() { store.clear() }

    fun clear() { store.clear() }
}

// ── MessageDao ────────────────────────────────────────────────────────

class FakeMessageDao : MessageDao {

    private data class IdKey(val id: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdKey) return false
            return id.contentEquals(other.id)
        }
        override fun hashCode(): Int = id.contentHashCode()
    }

    private val store = LinkedHashMap<IdKey, MessageEntity>()
    private val changeCounter = MutableStateFlow(0)

    override suspend fun upsert(message: MessageEntity) {
        store[IdKey(message.id)] = message
        changeCounter.value++
    }

    override fun threadFlow(peerPub: ByteArray): Flow<List<MessageEntity>> =
        changeCounter.map {
            store.values
                .filter { it.threadPub.contentEquals(peerPub) }
                .sortedBy { it.createdAt }
        }

    override suspend fun threadSnapshot(peerPub: ByteArray): List<MessageEntity> =
        store.values
            .filter { it.threadPub.contentEquals(peerPub) }
            .sortedBy { it.createdAt }

    override fun latestPerThreadFlow(): Flow<List<MessageEntity>> =
        changeCounter.map {
            store.values
                .groupBy { PubKey(it.threadPub) }
                .mapValues { (_, msgs) -> msgs.maxByOrNull { it.createdAt }!! }
                .values
                .sortedByDescending { it.createdAt }
        }

    override suspend fun unreadCountFor(peerPub: ByteArray): Int =
        store.values.count {
            it.threadPub.contentEquals(peerPub) &&
                it.fromPub.contentEquals(peerPub) &&
                it.status != "read"
        }

    override suspend fun pendingOutboundFrom(selfPub: ByteArray): List<MessageEntity> =
        store.values
            .filter { it.status == "pending" && it.fromPub.contentEquals(selfPub) }
            .sortedBy { it.createdAt }

    override suspend fun updateStatus(id: ByteArray, status: String) {
        val key = IdKey(id)
        store.computeIfPresent(key) { _, v -> v.copy(status = status) }
        changeCounter.value++
    }

    override suspend fun delete(id: ByteArray) {
        store.remove(IdKey(id))
        changeCounter.value++
    }

    override suspend fun byId(id: ByteArray): MessageEntity? = store[IdKey(id)]

    override fun totalUnreadFlow(): Flow<Int> =
        changeCounter.map {
            store.values.count { it.status != "read" }
        }

    fun clear() { store.clear(); changeCounter.value = 0 }

    private data class PubKey(val pub: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PubKey) return false
            return pub.contentEquals(other.pub)
        }
        override fun hashCode(): Int = pub.contentHashCode()
    }
}
